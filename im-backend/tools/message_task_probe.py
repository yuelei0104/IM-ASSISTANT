#!/usr/bin/env python3
"""Check that confirmed personal tasks link only to currently visible group messages.

Creates three disposable accounts in the local development database. Requires
user-service (dev profile), chat-service, MySQL and Redis; no model is needed.
"""

import argparse
import sys
import uuid

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


def task(chat_url, token, title, group_id, message_id):
    return expect("create sourced task", call(chat_url, "/api/chats/tasks", token, {
        "title": title, "sourceGroupId": group_id, "sourceMessageId": message_id,
    }), 201)


def tasks(chat_url, token):
    return expect("list tasks", call(chat_url, "/api/chats/tasks", token), 200)["items"]


def send(chat_url, path, token, body):
    return expect("send", call(chat_url, path + "/messages", token, {
        "clientMsgId": str(uuid.uuid4()), "body": body, "mentionedUserIds": [],
    }), 200)


def source(item, group_id, message_id):
    check(item["sourceGroupId"] == group_id and item["sourceMessageId"] == message_id,
          "source visibility differs from expected state")


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

    group = expect("create group", call(args.chat_url, "/api/chats/groups", owner, {
        "name": "待办来源验收", "friendIds": [member_id],
    }), 200)
    group_id = group["id"]
    path = f"/api/chats/groups/{group_id}"
    first = send(args.chat_url, path, owner, "开会时讨论只在群里可见的内容")
    task_path = "/api/chats/tasks"
    expect("outsider cannot attach source", call(args.chat_url, task_path, outsider, {
        "title": "窃取来源", "sourceGroupId": group_id, "sourceMessageId": first["id"],
    }), 403)
    expect("reject partial reference", call(args.chat_url, task_path, member, {
        "title": "不完整来源", "sourceGroupId": group_id,
    }), 400)
    saved = task(args.chat_url, member, "确认后私人的待办", group_id, first["id"])
    source(saved, group_id, first["id"])
    check(not tasks(args.chat_url, owner) and not tasks(args.chat_url, outsider),
          "another user can read the private task")

    expect("recall", call(args.chat_url, path + f"/messages/{first['id']}", owner,
                          method="DELETE"), 204)
    source(tasks(args.chat_url, member)[0], None, None)
    check(tasks(args.chat_url, member)[0]["title"] == "确认后私人的待办",
          "owner's confirmed title was erased")
    expect("cannot attach recalled source", call(args.chat_url, task_path, member, {
        "title": "已撤回", "sourceGroupId": group_id, "sourceMessageId": first["id"],
    }), 404)

    second = send(args.chat_url, path, owner, "退出前的消息")
    task(args.chat_url, member, "退出前确认的待办", group_id, second["id"])
    expect("leave", call(args.chat_url, path + "/members/me", member,
                         method="DELETE"), 204)
    source(tasks(args.chat_url, member)[0], None, None)
    expect("cannot attach after leaving", call(args.chat_url, task_path, member, {
        "title": "已退出", "sourceGroupId": group_id, "sourceMessageId": second["id"],
    }), 403)
    expect("reinvite", call(args.chat_url, path + "/members", owner,
                             {"friendIds": [member_id]}), 200)
    source(tasks(args.chat_url, member)[0], None, None)
    expect("cannot attach pre-rejoin source", call(args.chat_url, task_path, member, {
        "title": "重入后旧消息", "sourceGroupId": group_id, "sourceMessageId": second["id"],
    }), 404)
    third = send(args.chat_url, path, owner, "重新加入后的消息")
    task(args.chat_url, member, "重新加入后确认", group_id, third["id"])
    source(tasks(args.chat_url, member)[0], group_id, third["id"])
    expect("dissolve", call(args.chat_url, path, owner, method="DELETE"), 204)
    remaining = tasks(args.chat_url, member)
    check(len(remaining) == 3 and all(item["sourceGroupId"] is None and
          item["sourceMessageId"] is None for item in remaining),
          "dissolved group exposes source or deleted private tasks")
    print("PASS: confirmed tasks are private; source links follow recall, membership and dissolution")
    print("Three disposable accounts and their private tasks remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
