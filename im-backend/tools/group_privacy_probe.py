#!/usr/bin/env python3
"""Small local acceptance check for group captions and membership boundaries.

Creates three disposable accounts in the development database. It requires the
user service's dev profile, which returns registration codes in the response.
"""

import argparse
import json
import secrets
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class ProbeFailure(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


OPENER = build_opener(ProxyHandler({}), NoRedirect())


def loopback_url(value):
    parsed = urlparse(value)
    if (parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1")
            or parsed.username or parsed.password or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise argparse.ArgumentTypeError("Only loopback HTTP base URLs are allowed")
    return value.rstrip("/")


def call(base, path, token=None, body=None, method=None, timeout=12):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(base + path, data=data, headers=headers,
                      method=method or ("POST" if body is not None else "GET"))
    try:
        with OPENER.open(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
    except HTTPError as error:
        status = error.code
        raw = error.read()
    except (URLError, TimeoutError) as error:
        raise ProbeFailure("service unreachable: " + type(error).__name__) from error
    try:
        payload = json.loads(raw) if raw else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        payload = None
    return status, payload


def expect(label, result, status):
    actual, payload = result
    if actual != status:
        raise ProbeFailure(f"{label}: expected HTTP {status}, got {actual}")
    return payload


def check(condition, label):
    if not condition:
        raise ProbeFailure(label)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--check-summary", action="store_true",
                        help="also generate a pre-join summary; requires a running local model")
    args = parser.parse_args()
    suffix = uuid.uuid4().hex[:10]
    users = []
    for role in "abc":
        email = f"privacy-{suffix}-{role}@example.invalid"
        handle = f"privacy_{suffix}_{role}"
        password = secrets.token_urlsafe(18) + "Aa1"
        code = expect("dev registration code", call(args.user_url, "/api/auth/codes", body={
            "purpose": "register", "contactType": "email", "contact": email,
        }), 200)
        if not isinstance(code, dict) or not code.get("devCode"):
            raise ProbeFailure("devCode unavailable: start user-service with the dev profile")
        account = expect("register", call(args.user_url, "/api/auth/register", body={
            "nickname": f"隐私测试 {role.upper()}", "handle": handle, "email": email,
            "password": password, "emailCode": code["devCode"],
        }), 201)
        login = expect("login", call(args.user_url, "/api/auth/login/password", body={
            "contactType": "email", "contact": email, "password": password,
        }), 200)
        check(isinstance(account.get("id"), int) and bool(login.get("token")), "invalid account/login response")
        users.append((account["id"], handle, login["token"]))
    (owner, _, a), (member, _, b), (newcomer, newcomer_handle, c) = users

    for target_id, target_handle, recipient in ((member, users[1][1], b), (newcomer, newcomer_handle, c)):
        expect("friend request", call(args.user_url, "/api/contacts/requests", a,
                                      {"handle": target_handle}), 202)
        requests = expect("list friend requests", call(args.user_url, "/api/contacts/requests", recipient), 200)
        pending = [item for item in requests["items"] if item["fromId"] == owner
                   and item["toId"] == target_id and item["state"] == "PENDING"]
        check(len(pending) == 1, "friend request not visible to recipient")
        expect("accept friend request", call(args.user_url,
               f"/api/contacts/requests/{pending[0]['id']}/accept", recipient, body={}), 204)

    group = expect("create group", call(args.chat_url, "/api/chats/groups", a,
                                        {"name": "隐私边界测试", "friendIds": [member]}), 200)
    group_id = group["id"]
    path = f"/api/chats/groups/{group_id}"
    old = expect("send pre-join caption", call(args.chat_url, path + "/messages", a, {
        "clientMsgId": str(uuid.uuid4()), "body": "仅原成员可见的字幕",
        "mentionedUserIds": [], "kind": "CAPTION",
    }), 200)
    old_id = old["id"]
    before = expect("original member sees caption", call(args.chat_url, path + "/messages", b), 200)
    check(any(m["id"] == old_id and m["kind"] == "CAPTION" for m in before["messages"]),
          "original member cannot see caption")

    for label, suffix_path, method, body in (
        ("outsider history", "/messages", None, None),
        ("outsider sync", "/messages/sync?afterId=0", None, None),
        ("outsider search", "/messages/search?q=%E5%AD%97%E5%B9%95", None, None),
        ("outsider message", f"/messages/{old_id}", None, None),
        ("outsider summaries", "/summaries", None, None),
        ("outsider summary generation", "/summaries", "POST", {}),
        ("outsider caption send", "/messages", "POST", {
            "clientMsgId": str(uuid.uuid4()), "body": "不得发送",
            "mentionedUserIds": [], "kind": "CAPTION",
        }),
    ):
        expect(label, call(args.chat_url, path + suffix_path, c, body, method), 403)

    summary_id = None
    if args.check_summary:
        summary = expect("generate pre-join summary",
                         call(args.chat_url, path + "/summaries", a, body={}, timeout=60), 200)
        summary_id = summary["id"]

    expect("invite newcomer", call(args.chat_url, path + "/members", a,
                                   {"friendIds": [newcomer]}), 200)
    history = expect("newcomer history", call(args.chat_url, path + "/messages", c), 200)
    sync = expect("newcomer sync", call(args.chat_url, path + "/messages/sync?afterId=0", c), 200)
    search = expect("newcomer search", call(args.chat_url,
                                             path + "/messages/search?q=%E5%AD%97%E5%B9%95", c), 200)
    check(not history["messages"] and not sync["messages"] and not search["messages"],
          "newcomer can read pre-join caption")
    expect("newcomer direct lookup", call(args.chat_url, f"{path}/messages/{old_id}", c), 404)
    listed = expect("newcomer summaries", call(args.chat_url, path + "/summaries", c), 200)
    check(summary_id is None or all(item["id"] != summary_id for item in listed),
          "newcomer can read a pre-join summary")

    new = expect("send post-join caption", call(args.chat_url, path + "/messages", a, {
        "clientMsgId": str(uuid.uuid4()), "body": "新成员可见的字幕",
        "mentionedUserIds": [], "kind": "CAPTION",
    }), 200)
    history = expect("newcomer new history", call(args.chat_url, path + "/messages", c), 200)
    check([m["id"] for m in history["messages"]] == [new["id"]],
          "newcomer did not see exactly the post-join caption")

    expect("owner cannot leave", call(args.chat_url, path + "/members/me", a, method="DELETE"), 409)
    expect("member cannot remove someone else", call(args.chat_url,
           path + f"/members/{member}", c, method="DELETE"), 403)
    mention = expect("mention current member", call(args.chat_url, path + "/messages", a, {
        "clientMsgId": str(uuid.uuid4()), "body": "仅在群内可见的点名内容",
        "mentionedUserIds": [newcomer], "kind": "TEXT",
    }), 200)
    inbox = expect("member notice", call(args.chat_url, "/api/chats/notices", c), 200)
    check(any(item["messageId"] == mention["id"] for item in inbox["items"]),
          "current member cannot see own mention")

    expect("owner removes member", call(args.chat_url,
           path + f"/members/{newcomer}", a, method="DELETE"), 204)
    expect("removed member cannot read history", call(args.chat_url, path + "/messages", c), 403)
    expect("removed member cannot read summaries", call(args.chat_url, path + "/summaries", c), 403)
    removed_inbox = expect("removed member notices", call(args.chat_url, "/api/chats/notices", c), 200)
    check(not any(item["groupId"] == group_id for item in removed_inbox["items"])
          and removed_inbox["unreadCount"] == 0, "removed member can still read old mention")

    expect("reinvite member", call(args.chat_url, path + "/members", a,
           {"friendIds": [newcomer]}), 200)
    rejoined = expect("rejoined member history", call(args.chat_url, path + "/messages", c), 200)
    check(not rejoined["messages"], "rejoined member can read pre-rejoin messages")
    rejoined_inbox = expect("rejoined member notices", call(args.chat_url, "/api/chats/notices", c), 200)
    check(not any(item["groupId"] == group_id for item in rejoined_inbox["items"]),
          "rejoined member can read pre-rejoin mention")
    expect("member leaves", call(args.chat_url, path + "/members/me", c, method="DELETE"), 204)
    expect("former member cannot send", call(args.chat_url, path + "/messages", c, {
        "clientMsgId": str(uuid.uuid4()), "body": "不得发送", "mentionedUserIds": [],
    }), 403)
    expect("nonowner cannot transfer", call(args.chat_url, path + "/owner", b,
           {"memberId": owner}), 403)
    expect("owner cannot transfer to former member", call(args.chat_url, path + "/owner", a,
           {"memberId": newcomer}), 400)
    transferred = expect("transfer to current member", call(args.chat_url, path + "/owner", a,
           {"memberId": member}), 200)
    check(transferred["ownerId"] == member and set(transferred["memberIds"]) == {owner, member},
          "owner transfer changed membership")
    expect("former owner cannot dissolve", call(args.chat_url, path, a, method="DELETE"), 403)
    expect("former owner leaves", call(args.chat_url, path + "/members/me", a, method="DELETE"), 204)
    expect("new owner dissolves", call(args.chat_url, path, b, method="DELETE"), 204)
    for label, token in (("former owner", a), ("new owner", b), ("former member", c)):
        groups = expect(label + " groups", call(args.chat_url, "/api/chats/groups", token), 200)
        check(all(item["id"] != group_id for item in groups["items"]),
              label + " still lists dissolved group")
        expect(label + " cannot read dissolved group", call(args.chat_url, path, token), 403)
    expect("cannot send to dissolved group", call(args.chat_url, path + "/messages", b, {
        "clientMsgId": str(uuid.uuid4()), "body": "不得发送", "mentionedUserIds": [],
    }), 404)
    print("PASS: outsider denied; pre-join captions hidden across history/sync/search/direct lookup;")
    print("      post-join caption visible; leave/removal revoke access and mentions; rejoin hides old content;"
          " ownership transfer and dissolution enforced"
          + ("; pre-join summary hidden" if summary_id else ""))
    if not args.check_summary:
        print("Summary content boundary not exercised (use --check-summary with a local model).")
    print("Three disposable accounts remain in the local development database; the test group is dissolved.")


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
