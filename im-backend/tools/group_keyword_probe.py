#!/usr/bin/env python3
"""Check saved keyword alerts, deduplication and group membership boundaries.

Uses disposable accounts and local dev services on ports 8081 and 8082.
"""

import argparse
import sys
import uuid

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


def send(chat_url, path, token, body, mentioned=(), kind="TEXT", client_id=None):
    return expect("group send", call(chat_url, path + "/messages", token, {
        "clientMsgId": client_id or str(uuid.uuid4()), "body": body,
        "mentionedUserIds": list(mentioned), "kind": kind,
    }), 200)


def notices(chat_url, token):
    return expect("notices", call(chat_url, "/api/chats/notices", token), 200)["items"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    args = parser.parse_args()
    owner_id, _, owner = new_account(args.user_url, "a")
    member_id, member_handle, member = new_account(args.user_url, "b")
    _, _, outsider = new_account(args.user_url, "c")
    expect("friend request", call(args.user_url, "/api/contacts/requests", owner,
                                  {"handle": member_handle}), 202)
    requests = expect("friend requests", call(args.user_url, "/api/contacts/requests", member), 200)
    pending = [item for item in requests["items"] if item["fromId"] == owner_id
               and item["state"] == "PENDING"]
    check(len(pending) == 1, "friend request missing")
    expect("accept", call(args.user_url, f"/api/contacts/requests/{pending[0]['id']}/accept",
                          member, body={}), 204)

    keywords_url = "/api/chats/notices/keywords"
    expect("invalid keyword", call(args.chat_url, keywords_url, member,
                                   {"words": ["x"]}, method="PUT"), 400)
    expect("save member keywords", call(args.chat_url, keywords_url, member,
                                         {"words": ["会议", "截止"]}, method="PUT"), 200)
    expect("save owner keyword", call(args.chat_url, keywords_url, owner,
                                       {"words": ["会议"]}, method="PUT"), 200)
    expect("save outsider keyword", call(args.chat_url, keywords_url, outsider,
                                          {"words": ["会议"]}, method="PUT"), 200)
    stored = expect("member keywords persisted", call(args.chat_url, keywords_url, member), 200)
    check(set(stored["words"]) == {"会议", "截止"}, "keywords did not persist")

    group = expect("create group", call(args.chat_url, "/api/chats/groups", owner,
                                        {"name": "关注词验收", "friendIds": [member_id]}), 200)
    path = f"/api/chats/groups/{group['id']}"
    client_id = str(uuid.uuid4())
    first = send(args.chat_url, path, owner, "会议截止通知", client_id=client_id)
    check(sum(n["messageId"] == first["id"] and n["kind"] == "KEYWORD"
              for n in notices(args.chat_url, member)) == 1,
          "two matching words must produce one keyword alert")
    check(notices(args.chat_url, owner) == [] and notices(args.chat_url, outsider) == [],
          "sender or outsider received a group alert")
    send(args.chat_url, path, owner, "会议截止通知", client_id=client_id)
    check(sum(n["messageId"] == first["id"] for n in notices(args.chat_url, member)) == 1,
          "retry duplicated the alert")

    mention = send(args.chat_url, path, owner, "会议请看", [member_id])
    check(sum(n["messageId"] == mention["id"] and n["kind"] == "MENTION"
              for n in notices(args.chat_url, member)) == 1,
          "explicit mention must override matching keyword")
    expect("recall keyword message", call(args.chat_url, path + f"/messages/{first['id']}", owner,
                                          method="DELETE"), 204)
    check(all(n["messageId"] != first["id"] for n in notices(args.chat_url, member)),
          "recalled content remained in notifications")

    expect("member leaves", call(args.chat_url, path + "/members/me", member,
                                 method="DELETE"), 204)
    send(args.chat_url, path, owner, "会议仅剩余成员可见")
    check(notices(args.chat_url, member) == [], "former member can still see old alerts")
    expect("reinvite member", call(args.chat_url, path + "/members", owner,
                                   {"friendIds": [member_id]}), 200)
    check(notices(args.chat_url, member) == [], "rejoined member can see alerts from old membership")
    caption = send(args.chat_url, path, owner, "会议字幕片段", kind="CAPTION")
    check(any(n["messageId"] == caption["id"] and n["kind"] == "KEYWORD"
              for n in notices(args.chat_url, member)), "post-rejoin caption did not alert member")
    expect("dissolve group", call(args.chat_url, path, owner, method="DELETE"), 204)
    print("PASS: saved keywords, one alert per message, mention precedence, recall cleanup and membership privacy")
    print("Three disposable accounts remain in the local development database; the test group is dissolved.")


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
