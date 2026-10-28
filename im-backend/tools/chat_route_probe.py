#!/usr/bin/env python3
"""Check that one account registers two live chat-node routes and cleans them up.

Run from the backend directory with the dev user service, both chat nodes and
the project's Docker Redis running. Creates one disposable account, no messages.
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

from cluster_probe import ChatSocket
from group_privacy_probe import ProbeFailure, check, loopback_url
from message_recall_probe import new_account


BACKEND = Path(__file__).resolve().parent.parent


def routes(user_id):
    key = f"im:chat:routes:{user_id}"
    result = subprocess.run(
        ["docker", "compose", "--profile", "kafka", "exec", "-T", "redis",
         "redis-cli", "--raw", "ZRANGEBYSCORE", key,
         str(int(time.time() * 1000) + 1), "+inf"],
        cwd=BACKEND, text=True, capture_output=True, timeout=10, check=False,
    )
    if result.returncode:
        raise ProbeFailure("Redis route query failed; check docker compose ps")
    return {row for row in result.stdout.splitlines() if row}


def wait_routes(user_id, ports, label):
    deadline = time.monotonic() + 12
    while True:
        current = routes(user_id)
        present = {int(row.split("-", 1)[0]) for row in current if "-" in row}
        if present == ports and len(current) == len(ports):
            return
        if time.monotonic() >= deadline:
            raise ProbeFailure(f"{label}: expected node ports {sorted(ports)}, got {sorted(present)}")
        time.sleep(0.25)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--node-a-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--node-b-url", type=loopback_url, default="http://127.0.0.1:8083")
    args = parser.parse_args()
    ports = {8082, 8083}
    check({int(args.node_a_url.rsplit(":", 1)[1]), int(args.node_b_url.rsplit(":", 1)[1])} == ports,
          "route probe currently checks local chat ports 8082 and 8083")
    user_id, _, token = new_account(args.user_url, "r")
    with ChatSocket(args.node_a_url, token):
        wait_routes(user_id, {8082}, "first connection")
        with ChatSocket(args.node_b_url, token):
            wait_routes(user_id, ports, "both nodes connected")
        wait_routes(user_id, {8082}, "second node disconnected")
    wait_routes(user_id, set(), "all sockets disconnected")
    print("PASS: one user routes to both nodes; closing each socket removes only its own lease")
    print("One disposable account remains in the local development database.")


if __name__ == "__main__":
    try:
        main()
    except (ProbeFailure, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
