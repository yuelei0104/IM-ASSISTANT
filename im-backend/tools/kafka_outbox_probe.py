#!/usr/bin/env python3
"""Test Kafka outage/recovery without copying bearer tokens.

Run from im-backend with user-service (dev), two Kafka-enabled chat nodes,
MySQL, Redis, the optional Kafka container and the cluster gateway running.
The script temporarily stops only this Compose project's Kafka container;
its finally block restarts Kafka even if an assertion fails.
"""

import argparse
from pathlib import Path
import subprocess
import sys
import time
import uuid

from cluster_probe import ChatSocket
from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


def compose(*args):
    result = subprocess.run(["docker", "compose", "--profile", "kafka", *args],
                            cwd=Path(__file__).resolve().parents[1],
                            text=True, capture_output=True, check=False, timeout=120)
    if result.returncode:
        raise ProbeFailure("Docker Compose operation failed: " +
                           (result.stderr.strip().splitlines()[-1] if result.stderr.strip()
                            else "check 'docker compose --profile kafka ps --all'"))
    return result.stdout.strip()


def outbox_count(message_id, sender_id, recipient_id, published):
    payload = f"{message_id}:{sender_id}:{recipient_id}"
    clause = "IS NOT NULL" if published else "IS NULL"
    sql = ("SELECT COUNT(*) FROM im_notification_outbox WHERE payload='" + payload +
           "' AND published_at " + clause)
    output = compose("exec", "-T", "mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -uim_user im_assistant -e "$1"',
                     "outbox-check", sql)
    try:
        return int(output.splitlines()[-1])
    except (ValueError, IndexError) as error:
        raise ProbeFailure("Could not inspect notification outbox") from error


def wait_published(message_id, sender_id, recipient_id, stage, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if outbox_count(message_id, sender_id, recipient_id, True):
            return
        time.sleep(1)
    if stage == "baseline" and not outbox_count(message_id, sender_id, recipient_id, False):
        raise ProbeFailure("baseline message has no outbox row; restart both chat nodes "
                           "from the current jar with IM_KAFKA_ENABLED=true")
    raise ProbeFailure(stage + " outbox row remains unpublished; check Kafka and chat relay logs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8080")
    args = parser.parse_args()
    if not compose("ps", "--status", "running", "-q", "kafka"):
        raise ProbeFailure("Kafka is not running; run 'docker compose --profile kafka up -d kafka'")
    a_id, _, a = new_account(args.user_url, "a")
    b_id, b_handle, b = new_account(args.user_url, "b")
    expect("friend request", call(args.user_url, "/api/contacts/requests", a,
                                  {"handle": b_handle}), 202)
    pending = expect("friend requests", call(args.user_url, "/api/contacts/requests", b), 200)
    rows = [row for row in pending["items"] if row["fromId"] == a_id and row["state"] == "PENDING"]
    check(len(rows) == 1, "friend request missing")
    expect("accept", call(args.user_url, f"/api/contacts/requests/{rows[0]['id']}/accept",
                          b, body={}), 204)
    path = f"/api/chats/direct/{b_id}/messages"
    other_path = f"/api/chats/direct/{a_id}/messages"

    with ChatSocket(args.chat_url, b) as recipient:
        first = expect("baseline send", call(args.chat_url, path, a, {
            "clientMsgId": str(uuid.uuid4()), "body": "Kafka 基线通知",
        }), 200)
        recipient.expect_message(first["id"], a_id, b_id)
        wait_published(first["id"], a_id, b_id, "baseline", 15)

        stopped = False
        try:
            compose("stop", "kafka")
            stopped = True
            missed = expect("send while Kafka stopped", call(args.chat_url, path, a, {
                "clientMsgId": str(uuid.uuid4()), "body": "Kafka 暂停期间消息仍写入 MySQL",
            }), 200)
            check(outbox_count(missed["id"], a_id, b_id, False) == 1,
                  "pending outbox event was not stored with the committed message")
            recovered = expect("read while Kafka stopped", call(args.chat_url,
                other_path + f"/sync?afterId={first['id']}", b), 200)
            check([row["id"] for row in recovered["messages"]] == [missed["id"]],
                  "message was not durably readable during Kafka outage")
        finally:
            if stopped:
                compose("start", "kafka")

        recipient.connection.settimeout(90)
        expected = f"MSG {missed['id']} {a_id} {b_id}"
        previous = f"MSG {first['id']} {a_id} {b_id}"
        for _ in range(4):
            notice = recipient.receive()
            if notice == expected:
                break
            check(notice == previous, "unexpected notification during Kafka recovery")
        else:
            raise ProbeFailure("Kafka recovery did not notify the recipient")
        wait_published(missed["id"], a_id, b_id, "recovery")
        print("PASS: Kafka outage kept message and outbox durable; recovery replayed ID-only notice")
        print("Two disposable accounts and two messages remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except (ProbeFailure, OSError, ValueError, KeyError, TypeError,
            subprocess.TimeoutExpired) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
