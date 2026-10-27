"""Local, dependency-free checks for the benchmark's correctness gates."""

import unittest
from unittest.mock import patch

import message_benchmark as benchmark


class BenchmarkAnalysisTest(unittest.TestCase):
    def test_rejects_missing_duplicate_and_mismatched_commits(self):
        pair = benchmark.Pair(1, 2, "unused", "unused")
        items = [
            {"client_id": "a", "pair": 0, "body": "a", "result": {"id": 10}},
            {"client_id": "b", "pair": 0, "body": "b", "result": {"id": 11}},
            {"client_id": "c", "pair": 0, "body": "c", "result": {"id": 12}},
        ]
        rows = [{"clientMsgId": "a", "id": 10, "senderId": 1, "recipientId": 2, "body": "a"},
                {"clientMsgId": "a", "id": 10, "senderId": 1, "recipientId": 2, "body": "a"},
                {"clientMsgId": "b", "id": 18, "senderId": 1, "recipientId": 2, "body": "b"}]
        self.assertEqual(benchmark.verify_rows(items, rows, [pair]), {
            "observed": 3, "missing": 1, "duplicate_rows": 1, "unexpected": 0, "mismatch": 1,
        })

    def test_sync_reads_all_fifty_row_pages(self):
        pair = benchmark.Pair(1, 2, "unused", "unused")
        seen = []

        def fake_call(_base, path, _token):
            cursor = int(path.split("afterId=")[1].split("&")[0])
            seen.append(cursor)
            ids = list(range(cursor + 1, min(cursor + 50, 120) + 1))
            return 200, {"messages": [{"id": value} for value in ids],
                         "nextAfterId": ids[-1] if ids[-1] < 120 else None}

        with patch.object(benchmark, "call", side_effect=fake_call):
            self.assertEqual(len(benchmark.sync_pair("http://127.0.0.1:8080", pair)), 120)
        self.assertEqual(seen, [0, 50, 100])
        self.assertEqual(pair.cursor, 120)

    def test_outage_restarts_broker_when_sync_fails(self):
        calls = []

        def fake_compose(*args):
            calls.append(args)
            return "broker-id" if args[0] == "ps" else ""

        with patch.object(benchmark, "compose", side_effect=fake_compose), \
                patch.object(benchmark, "run_batch", side_effect=benchmark.ProbeFailure("sync failed")):
            with self.assertRaises(benchmark.ProbeFailure):
                benchmark.run_outage("http://127.0.0.1:8080",
                                     benchmark.Pair(1, 2, "unused", "unused"), 3, 12, "test")
        self.assertIn(("stop", "kafka"), calls)
        self.assertIn(("start", "kafka"), calls)


if __name__ == "__main__":
    unittest.main()
