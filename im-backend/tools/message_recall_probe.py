#!/usr/bin/env python3
"""Exercise direct and group message recall using two disposable local accounts.

Start the dev user service on 8081 and chat service on 8082. The script writes
test messages to local MySQL; no tokens or message bodies are printed.
"""

import argparse
import secrets
import sys
import uuid

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url


def new_account(user_url, role):
    suffix = uuid.uuid4().hex[:10]
    email = f"recall-{suffix}-{role}@example.invalid"
    handle = f"recall_{suffix}_{role}"
    password = secrets.token_urlsafe(18) + "Aa1"
    code = expect("registration code", call(user_url, "/api/auth/codes", body={
        "purpose": "register", "contactType": "email", "contact": email,
    }), 200)
    if not isinstance(code, dict) or not code.get("devCode"):
        raise ProbeFailure("devCode unavailable: run user-service with the dev profile")
    user = expect("register", call(user_url, "/api/auth/register", body={
        "nickname": f"撤回测试 {role}", "handle": handle, "email": email,
        "password": password, "emailCode": code["devCode"],
    }), 201)
    session = expect("login", call(user_url, "/api/auth/login/password", body={
        "contactType": "email", "contact": email, "password": password,
    }), 200)
    check(isinstance(user.get("id"), int) and bool(session.get("token")), "invalid login response")
    return user["id"], handle, session["token"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--check-summary", action="store_true",
                        help="verify summary invalidation; requires a configured local model")
    args = parser.parse_args()
    a_id, _, a = new_account(args.user_url, "a")
    b_id, b_handle, b = new_account(args.user_url, "b")
    expect("friend request", call(args.user_url, "/api/contacts/requests", a,
                                  {"handle": b_handle}), 202)
    requests = expect("friend requests", call(args.user_url, "/api/contacts/requests", b), 200)
    pending = [row for row in requests["items"] if row["fromId"] == a_id and row["state"] == "PENDING"]
    check(len(pending) == 1, "friend request missing")
    expect("accept", call(args.user_url, f"/api/contacts/requests/{pending[0]['id']}/accept", b,
                          body={}), 204)

    direct_path = f"/api/chats/direct/{b_id}/messages"
    client_id = str(uuid.uuid4())
    direct = expect("direct send", call(args.chat_url, direct_path, a, {
        "clientMsgId": client_id, "body": "私聊撤回探针",
    }), 200)
    message_id = direct["id"]
    check(any(row["id"] == message_id for row in expect("direct history", call(args.chat_url,
          f"/api/chats/direct/{a_id}/messages", b), 200)["messages"]), "recipient cannot read direct message")
    expect("recipient cannot recall direct", call(args.chat_url,
           f"/api/chats/direct/{a_id}/messages/{message_id}", b, method="DELETE"), 404)
    expect("direct recall", call(args.chat_url, f"{direct_path}/{message_id}", a,
                                 method="DELETE"), 204)
    expect("idempotent direct recall", call(args.chat_url, f"{direct_path}/{message_id}", a,
                                            method="DELETE"), 204)
    history = expect("direct tombstone", call(args.chat_url, f"/api/chats/direct/{a_id}/messages", b), 200)
    check(any(row["id"] == message_id and row["body"] == "" and row["recalledAt"]
              for row in history["messages"]), "direct content remained in history")
    search = expect("direct search", call(args.chat_url,
                    f"/api/chats/direct/{a_id}/messages/search?q=%E6%8E%A2%E9%92%88", b), 200)
    check(all(row["id"] != message_id for row in search["messages"]), "direct recall remained searchable")
    feed = expect("direct recall feed", call(args.chat_url,
                  f"/api/chats/direct/{a_id}/messages/recalls?afterId=0", b), 200)
    check(sum(row["messageId"] == message_id for row in feed["items"]) == 1,
          "direct recall feed is missing or duplicated")
    expect("recalled id cannot be reused", call(args.chat_url, direct_path, a, {
        "clientMsgId": client_id, "body": "私聊撤回探针",
    }), 409)

    group = expect("create group", call(args.chat_url, "/api/chats/groups", a, {
        "name": "撤回验证", "friendIds": [b_id],
    }), 200)
    group_path = f"/api/chats/groups/{group['id']}"
    group_message = expect("group send", call(args.chat_url, group_path + "/messages", a, {
        "clientMsgId": str(uuid.uuid4()), "body": "群聊撤回探针",
        "mentionedUserIds": [b_id], "kind": "TEXT",
    }), 200)
    group_message_id = group_message["id"]
    notices = expect("mention notice", call(args.chat_url, "/api/chats/notices", b), 200)
    check(any(row["messageId"] == group_message_id for row in notices["items"]), "mention notice missing")
    if args.check_summary:
        summary = expect("generate summary", call(args.chat_url, group_path + "/summaries", a,
                                                  body={}, timeout=60), 200)
        check(summary["id"] > 0, "summary generation failed")
    expect("other member cannot recall", call(args.chat_url,
           group_path + f"/messages/{group_message_id}", b, method="DELETE"), 404)
    expect("group recall", call(args.chat_url,
           group_path + f"/messages/{group_message_id}", a, method="DELETE"), 204)
    group_history = expect("group tombstone", call(args.chat_url, group_path + "/messages", b), 200)
    check(any(row["id"] == group_message_id and row["body"] == "" and row["recalledAt"]
              for row in group_history["messages"]), "group content remained in history")
    group_search = expect("group search", call(args.chat_url,
                          group_path + "/messages/search?q=%E6%8E%A2%E9%92%88", b), 200)
    check(all(row["id"] != group_message_id for row in group_search["messages"]),
          "group recall remained searchable")
    group_feed = expect("group recall feed", call(args.chat_url,
                        group_path + "/messages/recalls?afterId=0", b), 200)
    check(sum(row["messageId"] == group_message_id for row in group_feed["items"]) == 1,
          "group recall feed is missing or duplicated")
    notices = expect("cleared mention", call(args.chat_url, "/api/chats/notices", b), 200)
    check(not any(row["messageId"] == group_message_id for row in notices["items"]),
          "recalled mention still visible")
    if args.check_summary:
        summaries = expect("cleared summaries", call(args.chat_url, group_path + "/summaries", b), 200)
        check(all(row["id"] != summary["id"] for row in summaries),
              "summary containing recalled message remained visible")
    expect("dissolve group", call(args.chat_url, group_path, a, method="DELETE"), 204)
    print("PASS: direct and group recall authorization, tombstones, search, feeds and mention cleanup"
          + ("; summary invalidation" if args.check_summary else ""))
    print("Two disposable accounts and a direct-message tombstone remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
