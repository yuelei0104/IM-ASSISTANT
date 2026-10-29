"""Private single-host release stack. Run from any directory with Python 3."""

import argparse
import gzip
import hashlib
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ENV_FILE = ROOT / ".env"
COMPOSE_FILE = ROOT / "compose.yaml"
BACKUPS = ROOT / "backups"


def settings():
    if not ENV_FILE.is_file():
        raise RuntimeError(f"Copy {ROOT / '.env.example'} to {ENV_FILE} and set unique passwords")
    values = {}
    for line in ENV_FILE.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, sep, value = line.partition("=")
            if not sep:
                raise RuntimeError("Invalid .env line")
            values[key.strip()] = value.strip().strip('"').strip("'")
    names = ("IM_MYSQL_ROOT_PASSWORD", "IM_MYSQL_PASSWORD", "IM_PGVECTOR_PASSWORD")
    secrets = [values.get(name, "") for name in names]
    if any(len(value) < 16 or "replace-" in value or "change-this" in value for value in secrets):
        raise RuntimeError("Set three distinct passwords of at least 16 characters in deploy/.env")
    if len(set(secrets)) != 3:
        raise RuntimeError("The database passwords must be different")
    smtp = ("IM_SMTP_HOST", "IM_SMTP_USERNAME", "IM_SMTP_PASSWORD", "IM_SMTP_FROM")
    if any(values.get(name) for name in smtp) and not all(values.get(name) for name in smtp):
        raise RuntimeError("Configure all SMTP host, username, password and sender values together")
    port = int(values.get("IM_RELEASE_PORT", "18080"))
    if port < 1024 or port > 65535:
        raise RuntimeError("IM_RELEASE_PORT must be between 1024 and 65535")
    if values.get("IM_LOCAL_TEST_CODES", "false").lower() not in ("true", "false"):
        raise RuntimeError("IM_LOCAL_TEST_CODES must be true or false")
    return values, port


def initialize():
    if ENV_FILE.exists():
        raise RuntimeError("deploy/.env already exists; refusing to replace credentials")
    template = (ROOT / ".env.example").read_text(encoding="utf-8")
    for placeholder in ("replace-with-unique-random-root-secret",
                        "replace-with-unique-random-app-secret",
                        "replace-with-unique-random-vector-secret"):
        template = template.replace(placeholder, secrets.token_hex(24))
    descriptor = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        target.write(template)
    print(f"Created {ENV_FILE} with independent random database passwords")
    print("Set SMTP host, username, password and from address before testing email registration")


def compose(*args, project=None, env=None, check=True, **kwargs):
    command = ["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(COMPOSE_FILE)]
    if project:
        command.extend(["-p", project])
    command.extend(args)
    return subprocess.run(command, check=check, env=env, **kwargs)


def wait_http(url, expected_status, timeout=60):
    deadline = time.monotonic() + timeout
    last_result = "no response"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=4) as response:
                if response.status == expected_status:
                    return response.read()
                last_result = f"HTTP {response.status}"
        except urllib.error.HTTPError as error:
            if error.code == expected_status:
                return error.read()
            last_result = f"HTTP {error.code}"
        except (urllib.error.URLError, TimeoutError, OSError):
            last_result = "connection unavailable"
        time.sleep(2)
    raise RuntimeError(f"HTTP {expected_status} timed out for {url}; last result: {last_result}")


def websocket_check(port):
    request = (f"GET /api/chats/ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
               "Connection: Upgrade\r\nUpgrade: websocket\r\n"
               "Sec-WebSocket-Version: 13\r\n"
               "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
               f"Origin: http://127.0.0.1:{port}\r\n\r\n")
    deadline = time.monotonic() + 30
    while True:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
                connection.settimeout(5)
                connection.sendall(request.encode("ascii"))
                reply = connection.recv(2048).split(b"\r\n", 1)[0]
            if b" 101 " in reply:
                return
        except OSError:
            reply = b"no response"
        if time.monotonic() >= deadline:
            raise RuntimeError(f"WebSocket upgrade failed: {reply.decode('ascii', 'replace')}")
        time.sleep(2)


def smoke(port, project=None, env=None):
    url = f"http://127.0.0.1:{port}"
    body = wait_http(url + "/", 200)
    if b'id="app"' not in body:
        raise RuntimeError("Web response is not the built Vue app")
    unauthenticated = wait_http(url + "/api/auth/me", 401)
    if b"message" not in unauthenticated:
        raise RuntimeError("Gateway/user route did not return JSON")
    wait_http(url + "/api/chats/direct/unread", 401)
    websocket_check(port)
    compose("exec", "-T", "pgvector", "psql", "-U", "im_vector", "-d", "im_vectors",
            "-Atqc", "SELECT to_regclass('public.im_group_message_embedding')", project=project,
            env=env, stdout=subprocess.DEVNULL)
    compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
            "localhost:9092", "--describe", "--topic", "im.chat.notifications.v1",
            project=project, env=env, stdout=subprocess.DEVNULL)
    print(f"PASS: built web, user and chat HTTP, gateway WebSocket, vector table and Kafka topic at {url}")
    print("This smoke uses no account. Email delivery, messaging and Agent need separate user acceptance.")


def dump(command, output):
    with gzip.open(output, "wb") as target:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            while chunk := process.stdout.read(1024 * 1024):
                target.write(chunk)
            error = process.stderr.read()
            if process.wait() != 0:
                raise RuntimeError("Backup command failed: " + error.decode("utf-8", "replace")[-500:])
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
            process.stderr.close()


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def restore_file(command, path):
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    try:
        with gzip.open(path, "rb") as source:
            while chunk := source.read(1024 * 1024):
                process.stdin.write(chunk)
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError(f"Restore failed for {path.name}")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdin.close()


def backup():
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    destination = BACKUPS / stamp
    destination.mkdir(parents=True, exist_ok=False)
    base = ["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(COMPOSE_FILE), "exec", "-T"]
    mysql = destination / "mysql.sql.gz"
    vector = destination / "pgvector.dump.gz"
    try:
        dump(base + ["mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u im_user --single-transaction --routines --triggers im_assistant'], mysql)
        dump(base + ["pgvector", "pg_dump", "-U", "im_vector", "-d", "im_vectors", "--format=custom"], vector)
        manifest = {name: checksum(destination / name)
                    for name in (mysql.name, vector.name)}
        (destination / "sha256.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    except Exception:
        for path in destination.iterdir():
            path.unlink()
        destination.rmdir()
        raise
    print(f"Backup: {destination}")
    print("Keep it offline and encrypted. The snapshot contains account and message data.")


def restore_drill(folder):
    directory = Path(folder).resolve()
    manifest = json.loads((directory / "sha256.json").read_text(encoding="utf-8"))
    for name in ("mysql.sql.gz", "pgvector.dump.gz"):
        if checksum(directory / name) != manifest[name]:
            raise RuntimeError(f"Backup integrity failed for {name}")
    project = "im-assistant-restore-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    _, port = settings()
    drill_port = port + 1
    if drill_port > 65535:
        raise RuntimeError("Choose a release port below 65535 before running the restore drill")
    environment = os.environ.copy()
    environment["IM_RELEASE_PORT"] = str(drill_port)
    print(f"Restoring into isolated temporary project {project} (never the live volumes)")
    try:
        compose("up", "-d", "mysql", "redis", "pgvector", "kafka-init", "ollama",
                project=project, env=environment)
        # Wait for the one-shot topic job and databases before writing the dumps.
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            checks = (
                ("mysql", "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"),
                ("pgvector", "pg_isready", "-U", "im_vector", "-d", "im_vectors"),
                ("kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092",
                 "--describe", "--topic", "im.chat.notifications.v1"),
            )
            if all(compose("exec", "-T", *check, project=project, env=environment,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False).returncode == 0
                   for check in checks):
                break
            time.sleep(2)
        else:
            raise RuntimeError("Kafka topic initialization timed out")
        base = ["docker", "compose", "--env-file", str(ENV_FILE), "-f", str(COMPOSE_FILE),
                "-p", project, "exec", "-T"]
        restore_file(base + ["mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u im_user im_assistant'],
                     directory / "mysql.sql.gz")
        restore_file(base + ["pgvector", "pg_restore", "-U", "im_vector", "-d", "im_vectors",
                             "--clean", "--if-exists", "--no-owner"],
                     directory / "pgvector.dump.gz")
        compose("up", "-d", "user", "chat-a", "chat-b", "gateway", "web",
                project=project, env=environment)
        smoke(drill_port, project=project, env=environment)
        print("PASS: restored SQL and vectors in a disposable stack; live volumes were untouched")
    finally:
        compose("down", "--volumes", "--remove-orphans", project=project, env=environment, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("init", "config", "start", "models", "status", "smoke", "backup", "restore-drill", "stop"))
    parser.add_argument("backup_directory", nargs="?")
    args = parser.parse_args()
    if args.action == "init":
        initialize()
        return
    values, port = settings()
    if args.action == "config":
        compose("config", "--quiet")
        print("PASS: release configuration is valid")
    elif args.action == "start":
        compose("config", "--quiet")
        if values.get("IM_LOCAL_TEST_CODES", "false").lower() == "true":
            print("LOCAL TEST ONLY: verification codes will be returned to the browser; disable before sharing access")
        compose("up", "--build", "-d")
        smoke(port)
        print(f"Private release is running at http://127.0.0.1:{port}/")
        print("Run 'python deploy/manage.py models', then 'python deploy/manage.py smoke'.")
    elif args.action == "models":
        for model in ("qwen3:4b", "bge-m3"):
            compose("exec", "-T", "ollama", "ollama", "pull", model)
        print("PASS: both local model tags are present in the release container")
    elif args.action == "status":
        compose("ps", "--all")
    elif args.action == "smoke":
        smoke(port)
    elif args.action == "backup":
        backup()
    elif args.action == "restore-drill":
        if not args.backup_directory:
            parser.error("restore-drill requires a backup directory")
        restore_drill(args.backup_directory)
    elif args.action == "stop":
        compose("stop")
        print("Containers stopped; named volumes remain intact")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError, ValueError, KeyError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
