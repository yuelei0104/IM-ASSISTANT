#!/usr/bin/env python3
"""Verify private task dates and edits against local dev services (8081, 8082)."""

import argparse
import sys

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from message_recall_probe import new_account


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    args = parser.parse_args()

    _, _, owner = new_account(args.user_url, "a")
    _, _, outsider = new_account(args.user_url, "b")
    path = "/api/chats/tasks"
    saved = expect("create dated task", call(args.chat_url, path, owner, {
        "title": "明天确认", "dueDate": "2026-11-30",
    }), 201)
    task_id = saved["id"]
    check(saved["dueDate"] == "2026-11-30", "date did not persist at creation")
    check(saved["sourceGroupId"] is None, "plain task unexpectedly acquired a source")
    check(not expect("other user's list", call(args.chat_url, path, outsider), 200)["items"],
          "other user can see task")
    details = path + f"/{task_id}/details"
    expect("other user cannot edit", call(args.chat_url, details, outsider, {
        "title": "越权编辑", "dueDate": "2026-12-01",
    }, method="PUT"), 404)
    expect("reject blank title", call(args.chat_url, details, owner, {
        "title": "   ", "dueDate": None,
    }, method="PUT"), 400)
    expect("reject invalid date", call(args.chat_url, details, owner, {
        "title": "日期有误", "dueDate": "2026-02-30",
    }, method="PUT"), 400)
    edited = expect("edit and clear date", call(args.chat_url, details, owner, {
        "title": "最终标题", "dueDate": None,
    }, method="PUT"), 200)
    check(edited["title"] == "最终标题" and edited["dueDate"] is None,
          "date or title not updated")
    rows = expect("owner list", call(args.chat_url, path, owner), 200)["items"]
    check(len(rows) == 1 and rows[0]["title"] == "最终标题" and rows[0]["dueDate"] is None,
          "edited task not visible to owner")
    print("PASS: task dates persist, edits are private, invalid input is rejected and dates can be cleared")
    print("Two disposable accounts and one task remain in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
