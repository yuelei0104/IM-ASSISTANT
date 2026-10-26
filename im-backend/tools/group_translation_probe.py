#!/usr/bin/env python3
"""Verify local group translation preview and membership privacy boundaries.

Requires the dev user service, chat service, MySQL, Redis and a locally running
Ollama model configured through IM_SUMMARY_MODEL on chat-service. Creates three
disposable accounts and a group; sends only to loopback and never prints tokens.
"""

import argparse
import sys
import uuid

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


def preview(chat_url, path, message_id, token, language="en", timeout=12):
    return call(chat_url, f"{path}/messages/{message_id}/translation", token,
                {"targetLanguage": language}, timeout=timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    args = parser.parse_args()
    owner_id, _, owner = new_account(args.user_url, "a")
    member_id, member_handle, member = new_account(args.user_url, "b")
    newcomer_id, newcomer_handle, newcomer = new_account(args.user_url, "c")

    for target_id, target_handle, recipient in ((member_id, member_handle, member),
                                                (newcomer_id, newcomer_handle, newcomer)):
        expect("friend request", call(args.user_url, "/api/contacts/requests", owner,
                                      {"handle": target_handle}), 202)
        requests = expect("friend requests", call(args.user_url, "/api/contacts/requests",
                                                  recipient), 200)
        pending = [item for item in requests["items"] if item["fromId"] == owner_id
                   and item["toId"] == target_id and item["state"] == "PENDING"]
        check(len(pending) == 1, "friend request missing")
        expect("accept", call(args.user_url,
                              f"/api/contacts/requests/{pending[0]['id']}/accept",
                              recipient, body={}), 204)

    group = expect("create group", call(args.chat_url, "/api/chats/groups", owner, {
        "name": "本机翻译验收", "friendIds": [member_id],
    }), 200)
    path = f"/api/chats/groups/{group['id']}"
    old = expect("send pre-join message", call(args.chat_url, path + "/messages", owner, {
        "clientMsgId": str(uuid.uuid4()), "body": "明天上午九点开会",
        "kind": "TEXT", "mentionedUserIds": [],
    }), 200)
    message_id = old["id"]
    expect("outsider cannot translate", preview(args.chat_url, path, message_id, newcomer), 403)
    expect("invalid target rejected", preview(args.chat_url, path, message_id, member, "fr"), 400)

    expect("invite newcomer", call(args.chat_url, path + "/members", owner,
                                   {"friendIds": [newcomer_id]}), 200)
    expect("pre-join message hidden", preview(args.chat_url, path, message_id, newcomer), 404)
    result = expect("translate visible message", preview(args.chat_url, path,
                                                            message_id, member, timeout=60), 200)
    check(result["sourceMessageId"] == message_id and result["targetLanguage"] == "en"
          and isinstance(result["text"], str) and bool(result["text"].strip()),
          "translation preview has invalid contents")
    history = expect("translation does not send", call(args.chat_url, path + "/messages", member), 200)
    check([item["id"] for item in history["messages"]] == [message_id],
          "translation unexpectedly created a group message")

    expect("member leaves", call(args.chat_url, path + "/members/me", member, method="DELETE"), 204)
    expect("former member denied", preview(args.chat_url, path, message_id, member), 403)
    expect("message recalled", call(args.chat_url, path + f"/messages/{message_id}", owner,
                                    method="DELETE"), 204)
    expect("recalled source denied", preview(args.chat_url, path, message_id, owner), 404)
    print("PASS: translation is a private preview; outsider, pre-join, departed and recalled"
          " access denied; no message sent")
    print("Three disposable accounts and one recalled group message remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except (ProbeFailure, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
