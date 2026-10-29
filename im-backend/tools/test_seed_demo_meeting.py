"""Exercise the local demo fixture against a small in-memory HTTP API double."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

import seed_demo_meeting as demo


class FakeApi:
    def __init__(self):
        self.accounts = {}
        self.requests = []
        self.friends = set()
        self.group = None
        self.messages = {}
        self.fail_after = 65
        self.codes = []

    def __call__(self, base, path, token=None, body=None, method=None, timeout=12):
        parsed = urlsplit(path)
        route = parsed.path
        params = parse_qs(parsed.query)
        if route == "/api/auth/login/password":
            entry = next((account for account in self.accounts.values()
                          if body["contact"] in (account.get("email"), account.get("phone"))), None)
            if entry is None or entry["password"] != body["password"]:
                return 401, None
            return 200, {"token": f"token-{entry['id']}",
                         "account": {"id": entry["id"], "phone": entry.get("phone")}}
        if route == "/api/auth/codes":
            self.codes.append(body)
            return 200, {"devCode": "123456"}
        if route == "/api/auth/register":
            if body["phoneCode"] != "123456":
                return 400, None
            user_id = len(self.accounts) + 1
            self.accounts[user_id] = {"id": user_id, "password": body["password"],
                                      "phone": body["phone"], "handle": body["handle"]}
            return 201, {"id": user_id}
        user_id = int(token.split("-")[1]) if token else None
        if route == "/api/auth/bind":
            if body["contactType"] != "phone" or body["code"] != "123456":
                return 400, None
            if self.accounts[user_id]["password"] != body["password"]:
                return 401, None
            self.accounts[user_id]["phone"] = body["contact"]
            return 200, {"id": user_id, "phone": body["contact"]}
        if route.startswith("/api/contacts/") and route.endswith("/access"):
            other = int(route.split("/")[3])
            return (200, {"userId": user_id}) if (user_id, other) in self.friends else (403, None)
        if route == "/api/contacts/requests":
            if body is not None:
                other = next(entry["id"] for entry in self.accounts.values()
                             if entry["handle"] == body["handle"])
                if not any(row["fromId"] == user_id and row["toId"] == other
                           for row in self.requests):
                    self.requests.append({"id": len(self.requests) + 1, "fromId": user_id,
                                          "toId": other, "state": "PENDING"})
                return 202, None
            return 200, {"items": [row for row in self.requests if row["toId"] == user_id]}
        if route.endswith("/accept"):
            request_id = int(route.split("/")[-2])
            row = next(row for row in self.requests if row["id"] == request_id)
            row["state"] = "ACCEPTED"
            self.friends.add((row["fromId"], row["toId"]))
            return 204, None
        if route == "/api/chats/groups":
            if body is None:
                return 200, {"items": [self.group] if self.group else []}
            self.group = {"id": 901, "name": body["name"], "ownerId": user_id,
                          "memberIds": [user_id] + body["friendIds"]}
            return 200, self.group
        if route.startswith("/api/chats/groups/901/messages"):
            if user_id not in self.group["memberIds"]:
                return 403, None
            if body is not None:
                message_id = body["clientMsgId"]
                if message_id not in self.messages:
                    if self.fail_after is not None and len(self.messages) == self.fail_after:
                        self.fail_after = None
                        return 503, None
                    self.messages[message_id] = {"id": len(self.messages) + 1,
                                                  "body": body["body"], "senderId": user_id}
                return 200, self.messages[message_id]
            if route.endswith("/messages"):
                before = int(params.get("beforeId", [10**9])[0])
                rows = sorted((row for row in self.messages.values() if row["id"] < before),
                              key=lambda row: row["id"], reverse=True)
                page = rows[:50]
                return 200, {"messages": list(reversed(page)),
                             "nextBeforeId": page[-1]["id"] if len(rows) > 50 else None}
            return 200, None
        raise AssertionError(f"unexpected route: {route}")


class DemoMeetingTest(unittest.TestCase):
    def test_interrupted_seed_resumes_without_duplicate_rows_or_accounts(self):
        fake = FakeApi()
        with tempfile.TemporaryDirectory() as temp, patch.object(demo, "call", fake):
            path = Path(temp) / "demo-meeting.json"
            output = []
            with self.assertRaises(demo.ProbeFailure):
                demo.seed("http://127.0.0.1:8081", "http://127.0.0.1:8082",
                          path, progress=output.append)
            self.assertEqual(len(fake.messages), 65)
            self.assertNotIn("token-", path.read_text(encoding="utf-8"))
            first_id = demo.seed("http://127.0.0.1:8081", "http://127.0.0.1:8082",
                                 path, progress=output.append)
            self.assertEqual(first_id, 901)
            self.assertEqual(len(fake.accounts), 5)
            self.assertEqual(len(fake.group["memberIds"]), 4)
            self.assertEqual(len(fake.messages), 136)
            self.assertEqual(len(fake.requests), 3)
            self.assertEqual([row["contactType"] for row in fake.codes], ["phone"] * 5)
            demo.seed("http://127.0.0.1:8081", "http://127.0.0.1:8082",
                      path, progress=output.append)
            self.assertEqual(len(fake.messages), 136)
            people = json.loads(path.read_text(encoding="utf-8"))["people"]
            self.assertEqual(len(people), 5)
            self.assertEqual(len({person["phone"] for person in people}), 5)
            self.assertTrue(all(len(person["phone"]) == 11 for person in people))

    def test_old_email_fixture_binds_phone_without_replacing_existing_room(self):
        fake = FakeApi()
        fake.fail_after = None
        with tempfile.TemporaryDirectory() as temp, patch.object(demo, "call", fake):
            path = Path(temp) / "demo-meeting.json"
            state, _ = demo.fixture_state(path)
            for index, person in enumerate(state["people"]):
                person["email"] = f"meeting-{state['suffix']}-{index}@example.invalid"
                person.pop("phone")
                fake.accounts[index + 1] = {"id": index + 1, "email": person["email"],
                                            "phone": None, "password": person["password"],
                                            "handle": person["handle"]}
            path.write_text(json.dumps(state), encoding="utf-8")
            first_id = demo.seed("http://127.0.0.1:8081", "http://127.0.0.1:8082",
                                 path, progress=lambda _: None)
            self.assertEqual(first_id, 901)
            self.assertEqual(len(fake.accounts), 5)
            self.assertEqual(len(fake.messages), 136)
            self.assertEqual([row["purpose"] for row in fake.codes], ["bind"] * 5)
            self.assertTrue(all(fake.accounts[index + 1]["phone"] ==
                                demo.demo_phone(state["suffix"], index) for index in range(5)))
            demo.seed("http://127.0.0.1:8081", "http://127.0.0.1:8082",
                      path, progress=lambda _: None)
            self.assertEqual(len(fake.codes), 5)
            self.assertEqual(len(fake.messages), 136)


if __name__ == "__main__":
    unittest.main()
