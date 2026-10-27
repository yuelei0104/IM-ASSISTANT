#!/usr/bin/env python3
"""Bounded local IM baseline with disposable accounts and optional Kafka outage.

All targets must be loopback HTTP. The report contains no tokens or message text.
This measures HTTP send acknowledgement on a few conversations, not peak
cluster capacity or end-to-end WebSocket delivery latency.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
import json
import math
from pathlib import Path
import sys
import time
import uuid

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url
from kafka_outbox_probe import compose
from message_recall_probe import new_account


@dataclass
class Pair:
    sender_id: int
    recipient_id: int
    sender_token: str
    recipient_token: str
    cursor: int = 0


def sizes_arg(value):
    try:
        sizes = [int(part.strip()) for part in value.split(",")]
    except ValueError as error:
        raise argparse.ArgumentTypeError("Use comma-separated counts, e.g. 100,500,1000") from error
    if not sizes or len(sizes) > 3 or any(size < 1 or size > 1000 for size in sizes) \
            or sum(sizes) > 2000:
        raise argparse.ArgumentTypeError("Use at most three sizes (1..1000), totaling at most 2000")
    return sizes


def bounded_int(low, high):
    def parse(value):
        try:
            number = int(value)
        except ValueError as error:
            raise argparse.ArgumentTypeError("Expected an integer") from error
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f"Expected {low}..{high}")
        return number
    return parse


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(fraction * len(ordered)) - 1)], 2)


def metrics(base):
    result = {}
    for name in ("im.chat.outbox.pending", "im.chat.outbox.oldest.seconds",
                 "im.chat.outbox.relay.failures"):
        data = expect("chat metric " + name, call(base, "/actuator/metrics/" + name), 200)
        measurements = data.get("measurements", []) if isinstance(data, dict) else []
        value = measurements[0].get("value") if measurements else None
        result[name] = value if isinstance(value, (int, float)) and math.isfinite(value) else None
    return result


def setup_pair(user_url, index):
    # new_account prefixes a 10-character suffix; keep the handle <= 20 chars.
    a_id, _, a = new_account(user_url, f"{index}a")
    b_id, b_handle, b = new_account(user_url, f"{index}b")
    expect("friend request", call(user_url, "/api/contacts/requests", a,
                                  {"handle": b_handle}), 202)
    requests = expect("friend requests", call(user_url, "/api/contacts/requests", b), 200)
    pending = [item for item in requests["items"] if item["fromId"] == a_id
               and item["toId"] == b_id and item["state"] == "PENDING"]
    check(len(pending) == 1, "friend request missing")
    expect("accept friend request", call(user_url,
           f"/api/contacts/requests/{pending[0]['id']}/accept", b, body={}), 204)
    return Pair(a_id, b_id, a, b)


def send_one(gateway, pair, client_id, body, timeout):
    began = time.perf_counter()
    try:
        status, data = call(gateway, f"/api/chats/direct/{pair.recipient_id}/messages",
                            pair.sender_token, {"clientMsgId": client_id, "body": body}, timeout=timeout)
        message_id = data.get("id") if status == 200 and isinstance(data, dict) else None
        return {"status": status, "id": message_id if isinstance(message_id, int) else None,
                "latency_ms": (time.perf_counter() - began) * 1000}
    except (ProbeFailure, OSError, ValueError) as error:
        return {"status": None, "id": None,
                "latency_ms": (time.perf_counter() - began) * 1000,
                "error": type(error).__name__}


def sync_pair(gateway, pair):
    rows = []
    cursor = pair.cursor
    while True:
        page = expect("receiver sync", call(gateway,
            f"/api/chats/direct/{pair.sender_id}/messages/sync?afterId={cursor}&limit=50",
            pair.recipient_token), 200)
        batch = page["messages"]
        check(all(isinstance(row.get("id"), int) and row["id"] > cursor for row in batch),
              "sync did not advance")
        rows.extend(batch)
        next_cursor = page.get("nextAfterId")
        if next_cursor is None:
            if batch:
                cursor = batch[-1]["id"]
            pair.cursor = cursor
            return rows
        check(batch and next_cursor == batch[-1]["id"], "invalid sync cursor")
        cursor = next_cursor


def verify_rows(items, rows, pairs):
    expected = {item["client_id"]: item for item in items}
    counts = Counter(row["clientMsgId"] for row in rows)
    found = {row["clientMsgId"]: row for row in rows}
    missing = sum(client_id not in counts for client_id in expected)
    duplicate_rows = sum(max(count - 1, 0) for count in counts.values())
    unexpected = sum(count for client_id, count in counts.items() if client_id not in expected)
    mismatch = 0
    for client_id, item in expected.items():
        row = found.get(client_id)
        if row is not None and (row.get("senderId") != pairs[item["pair"]].sender_id
                or row.get("recipientId") != pairs[item["pair"]].recipient_id
                or row.get("body") != item["body"]
                or item["result"].get("id") is not None
                and row["id"] != item["result"]["id"]):
            mismatch += 1
    return {"observed": len(rows), "missing": missing, "duplicate_rows": duplicate_rows,
            "unexpected": unexpected, "mismatch": mismatch}


def run_batch(gateway, pairs, count, concurrency, timeout, run_id):
    items = [{"pair": i % len(pairs), "client_id": str(uuid.uuid4()),
              "body": f"bench-{run_id}-{i}"} for i in range(count)]
    began = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(send_one, gateway, pairs[item["pair"]], item["client_id"],
                                   item["body"], timeout): item for item in items}
        for future in as_completed(futures):
            futures[future]["result"] = future.result()
    elapsed = time.perf_counter() - began
    rows = []
    for pair_index, pair in enumerate(pairs):
        rows.extend(dict(row, _pair=pair_index) for row in sync_pair(gateway, pair))
    verified = verify_rows(items, rows, pairs)
    # A row belongs to the pair's dedicated conversation, not just its client ID.
    expected_pair = {item["client_id"]: item["pair"] for item in items}
    verified["mismatch"] += sum(row["clientMsgId"] in expected_pair and
                                 row["_pair"] != expected_pair[row["clientMsgId"]] for row in rows)
    successes = [item["result"]["latency_ms"] for item in items
                 if item["result"]["status"] == 200 and item["result"]["id"] is not None]
    failures = [{"item": i, "http_status": item["result"]["status"],
                 "error": item["result"].get("error")} for i, item in enumerate(items)
                if item["result"]["status"] != 200 or item["result"]["id"] is None]
    summary = {"attempted": count, "http_ok": len(successes), "http_failed": len(failures),
               "first_failures": failures[:5], "elapsed_s": round(elapsed, 3),
               "throughput_http_ok_per_s": round(len(successes) / elapsed, 2),
               "latency_ms": {"p50": percentile(successes, .50),
                              "p95": percentile(successes, .95),
                              "p99": percentile(successes, .99)}, **verified}
    summary["pass"] = not failures and not any(verified[key] for key in
        ("missing", "duplicate_rows", "unexpected", "mismatch"))
    return summary, items


def outbox_pending(ids, pair):
    # IDs come only from validated local JSON responses; never interpolate tokens.
    values = ",".join(f"'{int(message_id)}:{pair.sender_id}:{pair.recipient_id}'"
                      for message_id in ids)
    sql = ("SELECT COUNT(*) FROM im_notification_outbox WHERE published_at IS NULL "
           f"AND payload IN ({values})")
    result = compose("exec", "-T", "mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -uim_user im_assistant -e "$1"',
                     "outbox-benchmark", sql)
    try:
        return int(result.splitlines()[-1])
    except (ValueError, IndexError) as error:
        raise ProbeFailure("Could not inspect benchmark outbox rows") from error


def run_outage(gateway, pair, count, timeout, run_id):
    check(bool(compose("ps", "--status", "running", "-q", "kafka")),
          "Kafka must be running before --outage-count")
    stopped = False
    try:
        compose("stop", "kafka")
        stopped = True
        summary, items = run_batch(gateway, [pair], count, min(count, 5), timeout, run_id + "-off")
        ids = [item["result"]["id"] for item in items if item["result"]["id"] is not None]
        pending_while_stopped = outbox_pending(ids, pair) if ids else 0
    finally:
        if stopped:
            recovery_started = time.perf_counter()
            compose("start", "kafka")
    deadline = time.monotonic() + 120
    while ids and time.monotonic() < deadline and outbox_pending(ids, pair) > 0:
        time.sleep(1)
    recovery_s = round(time.perf_counter() - recovery_started, 2)
    pending_after = outbox_pending(ids, pair) if ids else 0
    summary.update({"pending_while_stopped": pending_while_stopped,
                    "pending_after_recovery": pending_after, "recovery_s": recovery_s})
    summary["pass"] = summary["pass"] and pending_while_stopped == count and pending_after == 0
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--gateway-url", type=loopback_url, default="http://127.0.0.1:8080")
    parser.add_argument("--node-a-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--node-b-url", type=loopback_url, default="http://127.0.0.1:8083")
    parser.add_argument("--sizes", type=sizes_arg, default=[100, 500, 1000])
    parser.add_argument("--concurrency", type=bounded_int(1, 30), default=5)
    parser.add_argument("--pairs", type=bounded_int(1, 4), default=2)
    parser.add_argument("--timeout", type=bounded_int(2, 30), default=12)
    parser.add_argument("--outage-count", type=bounded_int(0, 50), default=0,
                        help="opt in to stopping this Compose project's Kafka, then recover")
    parser.add_argument("--output", type=Path, help="JSON report path (default: benchmark-results/)")
    args = parser.parse_args()
    run_id = uuid.uuid4().hex[:10]
    report = {"run_id": run_id, "started_at": datetime.now().astimezone().isoformat(),
              "scope": "loopback HTTP acknowledgement across dedicated conversations; not peak capacity",
              "sizes": args.sizes, "concurrency": args.concurrency, "pairs": args.pairs,
              "outage_count": args.outage_count, "scenarios": [], "status": "FAIL"}
    output = args.output or (Path(__file__).resolve().parents[1] / "benchmark-results" /
                            f"message-baseline-{run_id}.json")
    try:
        for base in (args.user_url, args.gateway_url, args.node_a_url, args.node_b_url):
            expect("health " + base, call(base, "/actuator/health"), 200)
        check(bool(compose("ps", "--status", "running", "-q", "kafka")),
              "Kafka is not running in this Compose project")
        report["metrics_before"] = {"8082": metrics(args.node_a_url),
                                    "8083": metrics(args.node_b_url)}
        pairs = [setup_pair(args.user_url, i) for i in range(args.pairs)]
        for count in args.sizes:
            summary, _ = run_batch(args.gateway_url, pairs, count, args.concurrency, args.timeout,
                                   run_id + "-" + str(count))
            report["scenarios"].append(summary)
            print(f"{count} messages: {'PASS' if summary['pass'] else 'FAIL'}; "
                  f"HTTP {summary['http_ok']}/{count}, sync missing {summary['missing']}, "
                  f"p95 {summary['latency_ms']['p95']} ms")
            if not summary["pass"]:
                raise ProbeFailure("baseline acceptance failed; stopping before larger load")
        if args.outage_count:
            report["outage"] = run_outage(args.gateway_url, pairs[0], args.outage_count,
                                          args.timeout, run_id)
            if not report["outage"]["pass"]:
                raise ProbeFailure("Kafka outage recovery acceptance failed")
            print(f"Kafka recovery: PASS; outbox drained in {report['outage']['recovery_s']} s")
        report["metrics_after"] = {"8082": metrics(args.node_a_url),
                                   "8083": metrics(args.node_b_url)}
        report["status"] = "PASS"
    except (ProbeFailure, OSError, ValueError, KeyError, TypeError) as error:
        report["error"] = str(error) if isinstance(error, ProbeFailure) else type(error).__name__
        print("FAIL: " + report["error"], file=sys.stderr)
    except KeyboardInterrupt:
        report["error"] = "Interrupted by user"
        print("Interrupted; Kafka restart was attempted if the outage phase had begun.", file=sys.stderr)
    finally:
        report["finished_at"] = datetime.now().astimezone().isoformat()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                          encoding="utf-8")
        print(f"Report: {output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
