#!/usr/bin/env python3
"""Verify durable direct-chat read receipts with two disposable dev accounts.

Run against local user-service (dev profile) and chat-service after starting MySQL
and Redis. The script writes test accounts and messages to local MySQL.
"""

import argparse
import sys
import uuid

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    args = parser.parse_args()
    # new_account includes this role in the unique handle (maximum 20 characters).
    sender_id, _, sender = new_account(args.user_url, "a")
    reader_id, reader_handle, reader = new_account(args.user_url, "b")
    expect("friend request", call(args.user_url, "/api/contacts/requests", sender,
                                  {"handle": reader_handle}), 202)
    requests = expect("friend requests", call(args.user_url, "/api/contacts/requests", reader), 200)
    pending = [row for row in requests["items"] if row["fromId"] == sender_id
               and row["state"] == "PENDING"]
    check(len(pending) == 1, "friend request missing")
    expect("accept", call(args.user_url, f"/api/contacts/requests/{pending[0]['id']}/accept",
                          reader, body={}), 204)

    sender_path = f"/api/chats/direct/{reader_id}"
    reader_path = f"/api/chats/direct/{sender_id}"
    initial = expect("initial receipt", call(args.chat_url, sender_path + "/receipt", sender), 200)
    check(initial["lastReadMessageId"] == 0, "new conversation already has a read cursor")
    sent = []
    for index in (1, 2):
        message = expect(f"send message {index}", call(args.chat_url, sender_path + "/messages",
                                                         sender, {
            "clientMsgId": str(uuid.uuid4()), "body": f"已读回执测试 {index}",
        }), 200)
        sent.append(message["id"])
    unread = expect("reader unread", call(args.chat_url, "/api/chats/direct/unread", reader), 200)
    check(any(row["peerId"] == sender_id and row["unreadCount"] == 2 for row in unread),
          "reader unread count did not include both messages")
    expect("reader marks first", call(args.chat_url, reader_path + "/read", reader,
                                      {"lastReadMessageId": sent[0]}), 204)
    first = expect("sender sees first receipt", call(args.chat_url,
                   sender_path + "/receipt", sender), 200)
    check(first["lastReadMessageId"] == sent[0], "first receipt missing")
    expect("reader marks second", call(args.chat_url, reader_path + "/read", reader,
                                       {"lastReadMessageId": sent[1]}), 204)
    expect("reader repeats older mark", call(args.chat_url, reader_path + "/read", reader,
                                             {"lastReadMessageId": sent[0]}), 204)
    latest = expect("sender sees monotonic receipt", call(args.chat_url,
                    sender_path + "/receipt", sender), 200)
    check(latest["lastReadMessageId"] == sent[1], "read cursor moved backward")
    check(not any(row["peerId"] == sender_id for row in expect("cleared unread", call(args.chat_url,
          "/api/chats/direct/unread", reader), 200)), "reader unread badge was not cleared")

    other = expect("reader sends own message", call(args.chat_url, reader_path + "/messages", reader, {
        "clientMsgId": str(uuid.uuid4()), "body": "反向消息",
    }), 200)
    expect("cannot mark own message read", call(args.chat_url, reader_path + "/read", reader,
                                                {"lastReadMessageId": other["id"]}), 400)
    check(expect("receipt after invalid read", call(args.chat_url, sender_path + "/receipt",
                                                    sender), 200)["lastReadMessageId"] == sent[1],
          "invalid read changed the cursor")
    print("PASS: authenticated receipts, exact message cursor, monotonic reads, unread badge and invalid-read guard")
    print("Two disposable accounts and three direct messages remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
