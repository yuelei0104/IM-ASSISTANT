"""Offline checks of acceptance rules; no network, Docker or Ollama required."""

import unittest

from agent_acceptance import NO_EVIDENCE, ProbeFailure, require_answer, require_summary


class AgentAcceptanceRulesTest(unittest.TestCase):
    def setUp(self):
        self.messages = [
            {"id": 1, "body": "议题：新版登录页的需求评审定在周二。"},
            {"id": 2, "body": "结论：团队决定周五发布新版登录页。"},
            {"id": 3, "body": "待办：李明负责提交接口清单。"},
        ]
        self.summary = {"id": 4, "content": "议题：需求评审\n结论：周五发布\n待办：李明提交接口清单"}

    def test_exact_grounded_answer_and_summary(self):
        self.assertIn("李明", require_answer("factual", (200, {
            "answer": "李明负责提交接口清单。", "sources": [self.messages[2]],
        }), self.messages[2], "李明"))
        require_answer("unknown", (200, {"answer": NO_EVIDENCE, "sources": []}))
        require_summary(self.summary, self.messages, self.messages)

    def test_unrelated_or_leaked_citation_fails(self):
        with self.assertRaises(ProbeFailure):
            require_answer("leak", (200, {"answer": "李明负责。",
                                         "sources": [self.messages[1]]}),
                           self.messages[2], "李明")
        with self.assertRaises(ProbeFailure):
            require_answer("hallucination", (200, {"answer": "预算900万",
                                                  "sources": [self.messages[1]]}))

    def test_summary_missing_fact_or_wrong_source_fails(self):
        with self.assertRaisesRegex(ProbeFailure, "summary omitted the Friday decision; got: 结论：未提及"):
            require_summary({"id": 4, "content": "议题：需求评审\n结论：未提及\n待办：李明提交接口清单"},
                            self.messages, self.messages)
        with self.assertRaises(ProbeFailure):
            require_summary(self.summary, self.messages[:2], self.messages)

    def test_fixture_handle_stays_within_registration_limit(self):
        from unittest.mock import patch
        from agent_acceptance import register

        def fake_call(base, path, token=None, body=None, **kwargs):
            if path == "/api/auth/codes":
                return 200, {"devCode": "123456"}
            if path == "/api/auth/register":
                self.assertRegex(body["handle"], r"^[a-z0-9_]{3,20}$")
                return 201, {"id": 1}
            return 200, {"token": "test-session"}

        with patch("agent_acceptance.call", side_effect=fake_call):
            account_id, handle, token = register("http://127.0.0.1:8081", "a" * 10, "b")
        self.assertEqual((account_id, token), (1, "test-session"))
        self.assertEqual(handle, "ag_aaaaaaaaaa_b")


if __name__ == "__main__":
    unittest.main()
