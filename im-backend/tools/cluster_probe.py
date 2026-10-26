#!/usr/bin/env python3
"""Exercise two chat nodes, their Redis notifications and gateway recovery.

Run against local development services only. The full check creates two
disposable accounts and three direct messages in the development database.
After stopping either chat node, --gateway-only checks gateway health routing.
"""

import argparse
import base64
import hashlib
import secrets
import socket
import sys
import uuid
from urllib.parse import urlparse

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


class ChatSocket:
    """Small RFC 6455 text client for the local acceptance probe."""

    def __init__(self, base_url, token):
        parsed = urlparse(base_url)
        host = parsed.hostname
        port = parsed.port or 80
        self.connection = socket.create_connection((host, port), timeout=12)
        self.connection.settimeout(12)
        self.reader = self.connection.makefile("rb")
        key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        request = (f"GET /api/chats/ws HTTP/1.1\r\nHost: {host}:{port}\r\n"
                   "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                   "Origin: http://localhost:5173\r\n\r\n")
        self.connection.sendall(request.encode("ascii"))
        try:
            status = self.reader.readline(1024)
            headers = {}
            while True:
                line = self.reader.readline(4096)
                if line in (b"\r\n", b""):
                    break
                name, sep, value = line.partition(b":")
                if sep:
                    headers[name.lower()] = value.strip()
            expected = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
            ).digest())
            check(status.startswith(b"HTTP/1.1 101 ") and
                  headers.get(b"sec-websocket-accept") == expected,
                  f"WebSocket upgrade failed on port {port}")
            self.send("AUTH " + token)
            check(self.receive() == "READY", f"WebSocket authentication failed on port {port}")
        except Exception:
            self.close()
            raise

    def send(self, text, opcode=1):
        payload = text.encode("utf-8") if isinstance(text, str) else text
        check(len(payload) < 126, "probe frame is too long")
        mask = secrets.token_bytes(4)
        frame = bytes((0x80 | opcode, 0x80 | len(payload))) + mask
        self.connection.sendall(frame + bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload)))

    def receive(self):
        while True:
            header = self.reader.read(2)
            if len(header) != 2:
                raise ProbeFailure("WebSocket closed before notification arrived")
            opcode = header[0] & 0x0f
            size = header[1] & 0x7f
            if size == 126:
                size = int.from_bytes(self.reader.read(2), "big")
            elif size == 127:
                size = int.from_bytes(self.reader.read(8), "big")
            check(size <= 4096 and not header[1] & 0x80, "invalid server WebSocket frame")
            payload = self.reader.read(size)
            check(len(payload) == size, "incomplete server WebSocket frame")
            if opcode == 9:
                self.send(payload, opcode=10)
            elif opcode == 1:
                return payload.decode("utf-8")
            elif opcode == 8:
                raise ProbeFailure("WebSocket closed before notification arrived")

    def expect_message(self, message_id, sender_id, recipient_id):
        expected = f"MSG {message_id} {sender_id} {recipient_id}"
        check(self.receive() == expected, "cross-node WebSocket notice missing or unexpected")

    def close(self):
        self.reader.close()
        self.connection.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def gateway_check(user_url, gateway_url):
    _, _, token = new_account(user_url, "g")
    for _ in range(8):
        expect("gateway HTTP with surviving chat node",
               call(gateway_url, "/api/chats/direct/unread", token), 200)
    with ChatSocket(gateway_url, token):
        pass
    print("PASS: gateway HTTP and WebSocket reach a healthy chat node")
    print("One disposable account remains in the local development database.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--node-a-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--node-b-url", type=loopback_url, default="http://127.0.0.1:8083")
    parser.add_argument("--gateway-url", type=loopback_url, default="http://127.0.0.1:8080")
    parser.add_argument("--gateway-only", action="store_true",
                        help="run after stopping one node and waiting for gateway health check")
    args = parser.parse_args()
    if args.gateway_only:
        gateway_check(args.user_url, args.gateway_url)
        return
    a_id, _, a = new_account(args.user_url, "a")
    b_id, b_handle, b = new_account(args.user_url, "b")
    expect("friend request", call(args.user_url, "/api/contacts/requests", a,
                                  {"handle": b_handle}), 202)
    requests = expect("friend requests", call(args.user_url, "/api/contacts/requests", b), 200)
    pending = [item for item in requests["items"] if item["fromId"] == a_id
               and item["toId"] == b_id and item["state"] == "PENDING"]
    check(len(pending) == 1, "friend request missing")
    expect("accept", call(args.user_url, f"/api/contacts/requests/{pending[0]['id']}/accept",
                          b, body={}), 204)

    a_path = f"/api/chats/direct/{b_id}/messages"
    b_path = f"/api/chats/direct/{a_id}/messages"
    with ChatSocket(args.node_a_url, a) as socket_a, \
            ChatSocket(args.node_b_url, b) as socket_b, \
            ChatSocket(args.gateway_url, b) as gateway_b:
        first = expect("send on A", call(args.node_a_url, a_path, a, {
            "clientMsgId": str(uuid.uuid4()), "body": "跨实例通知 A 到 B",
        }), 200)
        socket_a.expect_message(first["id"], a_id, b_id)
        socket_b.expect_message(first["id"], a_id, b_id)
        gateway_b.expect_message(first["id"], a_id, b_id)
        check(any(row["id"] == first["id"] for row in expect("read on B", call(
            args.node_b_url, b_path + "/sync?afterId=0", b), 200)["messages"]),
            "node B cannot read the committed message")

        second = expect("send on B", call(args.node_b_url, b_path, b, {
            "clientMsgId": str(uuid.uuid4()), "body": "跨实例通知 B 到 A",
        }), 200)
        socket_a.expect_message(second["id"], b_id, a_id)

    # No A socket is connected when the next message commits. Pub/Sub can be
    # lost here, but MySQL sync must recover it after A reconnects.
    third = expect("send while A disconnected", call(args.gateway_url, b_path, b, {
        "clientMsgId": str(uuid.uuid4()), "body": "断线期间的消息",
    }), 200)
    with ChatSocket(args.gateway_url, a):
        recovered = expect("reconnect sync through gateway", call(args.gateway_url,
                           a_path + f"/sync?afterId={second['id']}", a), 200)
        check([row["id"] for row in recovered["messages"]] == [third["id"]],
              "reconnect did not recover exactly one missed message")
    print("PASS: cross-node push in both directions, gateway WebSocket, and durable reconnect sync")
    print("Two disposable accounts and three direct messages remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except (ProbeFailure, OSError, ValueError, KeyError, TypeError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
