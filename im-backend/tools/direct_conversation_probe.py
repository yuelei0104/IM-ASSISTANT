#!/usr/bin/env python3
"""Verify that clearing a direct conversation affects only the caller.

Run against local dev user/chat services. Creates two disposable accounts and
two direct messages in MySQL. It never deletes another account's data.
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
    a_id, _, a = new_account(args.user_url, "a")
    b_id, b_handle, b = new_account(args.user_url, "b")
    expect("friend request", call(args.user_url, "/api/contacts/requests", a,
                                  {"handle": b_handle}), 202)
    requests = expect("requests", call(args.user_url, "/api/contacts/requests", b), 200)
    pending = [r for r in requests["items"] if r["fromId"] == a_id and r["state"] == "PENDING"]
    check(len(pending) == 1, "friend request missing")
    expect("accept", call(args.user_url, f"/api/contacts/requests/{pending[0]['id']}/accept",
                          b, body={}), 204)
    base = "/api/chats/direct"
    a_path, b_path = f"{base}/{b_id}/messages", f"{base}/{a_id}/messages"
    first = expect("send before clear", call(args.chat_url, b_path, b,
                   {"clientMsgId": str(uuid.uuid4()), "body": "会话清空验收：旧消息"}), 200)
    check(any(r["peerId"] == b_id for r in expect("unread before clear",
          call(args.chat_url, base + "/unread", a), 200)), "old message is not unread")
    expect("clear A", call(args.chat_url, f"{base}/conversations/{b_id}", a,
                           method="DELETE"), 204)
    expect("clear A again", call(args.chat_url, f"{base}/conversations/{b_id}", a,
                                 method="DELETE"), 204)
    check(b_id in expect("A hidden list", call(args.chat_url,
          base + "/conversations/hidden", a), 200), "A's conversation did not disappear")
    check(a_id not in expect("B hidden list", call(args.chat_url,
          base + "/conversations/hidden", b), 200), "B's conversation was hidden")
    for label, path in (("history", a_path), ("sync", a_path + "/sync?afterId=0"),
                        ("search", a_path + "/search?q=%E4%BC%9A%E8%AF%9D%E6%B8%85%E7%A9%BA")):
        page = expect("A " + label, call(args.chat_url, path, a), 200)
        check(page["messages"] == [], "A can still read old " + label)
    check(not any(r["peerId"] == b_id for r in expect("unread after clear",
          call(args.chat_url, base + "/unread", a), 200)), "A's old unread survived")
    check(any(r["id"] == first["id"] for r in expect("B keeps history",
          call(args.chat_url, b_path, b), 200)["messages"]), "B lost their history")
    expect("friendship survives", call(args.user_url,
           f"/api/contacts/{b_id}/profile", a), 200)
    second = expect("new message", call(args.chat_url, b_path, b, {
        "clientMsgId": str(uuid.uuid4()), "body": "会话清空验收：新消息",
    }), 200)
    check(b_id not in expect("A reopened list", call(args.chat_url,
          base + "/conversations/hidden", a), 200), "new message did not reopen the chat")
    after = expect("A sync after new message", call(args.chat_url,
                   a_path + "/sync?afterId=0", a), 200)["messages"]
    check([r["id"] for r in after] == [second["id"]], "sync returned old or missed new message")
    print("PASS: only A clears old history/unread; B keeps history; friendship survives; new message reopens chat")
    print("Two disposable accounts and two messages remain in local dev MySQL.")


if __name__ == "__main__":
    try:
        main()
    except (ProbeFailure, OSError, ValueError, KeyError, TypeError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
