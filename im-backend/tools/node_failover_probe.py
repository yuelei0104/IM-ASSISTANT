#!/usr/bin/env python3
"""Fail over a recorded local chat node and verify push plus durable recovery.

Run only against the Windows stack started by tools/local_stack.ps1. This probe
stops its recorded chat-b process, then restores it in a finally block. It
creates two disposable accounts and three real direct messages in dev MySQL.
"""

import argparse
import subprocess
import sys
import time
import uuid
from pathlib import Path

from cluster_probe import ChatSocket
from group_privacy_probe import ProbeFailure, call, check, expect
from message_recall_probe import new_account


BACKEND = Path(__file__).resolve().parent.parent
STACK = BACKEND / "tools" / "local_stack.ps1"
USER = "http://127.0.0.1:8081"
NODE_A = "http://127.0.0.1:8082"
NODE_B = "http://127.0.0.1:8083"
GATEWAY = "http://127.0.0.1:8080"


def stack(action):
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-File", str(STACK), action],
        cwd=BACKEND, text=True, capture_output=True, timeout=180, check=False,
    )
    if result.returncode:
        # The local launcher can provide the full diagnostics with `status`.
        raise ProbeFailure(f"local_stack.ps1 {action} failed; run .\\tools\\local_stack.ps1 status")


def wait_gateway(token):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            status, _ = call(GATEWAY, "/api/chats/direct/unread", token)
            if status == 200:
                return
        except ProbeFailure:
            pass
        time.sleep(1)
    raise ProbeFailure("gateway did not route HTTP to surviving chat-a within 45 seconds")


def open_gateway_socket(token):
    deadline = time.monotonic() + 30
    while True:
        try:
            return ChatSocket(GATEWAY, token)
        except (ProbeFailure, OSError):
            if time.monotonic() >= deadline:
                raise ProbeFailure("gateway WebSocket did not switch to chat-a within 30 seconds")
            time.sleep(1)


def send(sender_token, recipient_id, body):
    return expect("send direct message", call(NODE_A,
        f"/api/chats/direct/{recipient_id}/messages", sender_token,
        {"clientMsgId": str(uuid.uuid4()), "body": body}), 200)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    check(sys.platform == "win32" and (BACKEND / ".local-stack" / "state.json").is_file(),
          "run on Windows after .\\tools\\local_stack.ps1 start -Build")
    for label, url in (("user", USER), ("chat-a", NODE_A),
                       ("chat-b", NODE_B), ("gateway", GATEWAY)):
        status, health = call(url, "/actuator/health")
        check(status == 200 and health.get("status") == "UP",
              f"{label} is not healthy; start the full local stack first")

    a_id, _, a = new_account(USER, "f")
    b_id, b_handle, b = new_account(USER, "f")
    expect("friend request", call(USER, "/api/contacts/requests", a,
                                  {"handle": b_handle}), 202)
    requests = expect("list friend requests", call(USER, "/api/contacts/requests", b), 200)
    pending = [row for row in requests["items"] if row["fromId"] == a_id
               and row["toId"] == b_id and row["state"] == "PENDING"]
    check(len(pending) == 1, "test friend request is missing")
    expect("accept", call(USER, f"/api/contacts/requests/{pending[0]['id']}/accept",
                          b, body={}), 204)

    paused = False
    try:
        with ChatSocket(NODE_A, a) as socket_a, ChatSocket(NODE_B, b) as socket_b:
            first = send(a, b_id, "节点故障验收：正常推送")
            socket_a.expect_message(first["id"], a_id, b_id)
            socket_b.expect_message(first["id"], a_id, b_id)

            stack("pause-chat-b")
            paused = True
            socket_b.close()
            wait_gateway(b)
            for _ in range(8):
                expect("gateway HTTP after node failure",
                       call(GATEWAY, "/api/chats/direct/unread", b), 200)

            missed = send(a, b_id, "节点故障验收：掉线补偿")
            socket_a.expect_message(missed["id"], a_id, b_id)
            with open_gateway_socket(b) as recovered_socket:
                rows = expect("reconnect sync through gateway", call(
                    GATEWAY, f"/api/chats/direct/{a_id}/messages/sync?afterId={first['id']}", b), 200)
                check([row["id"] for row in rows["messages"]] == [missed["id"]],
                      "reconnected user did not recover exactly the missed message")
                live = send(a, b_id, "节点故障验收：存活节点实时推送")
                socket_a.expect_message(live["id"], a_id, b_id)
                recovered_socket.expect_message(live["id"], a_id, b_id)
    finally:
        if paused:
            stack("resume-chat-b")
            status, health = call(NODE_B, "/actuator/health")
            check(status == 200 and health.get("status") == "UP", "chat-b did not recover")
            print("PASS: recorded chat-b process restored on port 8083")
    print("PASS: gateway switched to chat-a; reconnect sync recovered the missed message; live push resumed")
    print("Two disposable accounts and three direct messages remain in local dev MySQL.")


if __name__ == "__main__":
    try:
        main()
    except (ProbeFailure, OSError, KeyError, TypeError, ValueError,
            subprocess.TimeoutExpired) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
