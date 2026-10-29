# Private single-host deployment

This stack builds immutable Vue, Java 21, and Nginx images and runs one user
service, two chat nodes, a cluster gateway, MySQL, Redis, pgvector, Kafka and
Ollama. It uses a **separate Compose project and separate volumes** from the
local development stack. Only the Nginx web port is published, bound to
`127.0.0.1`; the databases, broker, model and Java endpoints are private to
the Docker network. It is a reproducible **private release/acceptance stack**.
The gateway listens on its container network interface so Nginx can reach it;
the gateway port is not published on the host.
Do not publish port 18080 to the internet as a substitute for TLS and trusted
reverse proxy configuration.

## Preparation on Windows

Install Docker Desktop, Python 3, and allocate enough memory and free disk for
MySQL, Kafka, two chat JVMs and the two Ollama models. Models in the Docker
volume are independent of models installed in Windows Ollama.

```powershell
Set-Location D:\im-assistant\im-backend
python .\deploy\manage.py init
notepad .\deploy\.env
python .\deploy\manage.py config
python .\deploy\manage.py start
python .\deploy\manage.py models
python .\deploy\manage.py smoke
```

`init` creates three different random database passwords and refuses to
overwrite an existing file. Leave `deploy/.env` out of Git. On a shared Windows machine, limit
that file's ACL to your own account. Do not reuse the development `.env` or
mount existing development data volumes. Changing database passwords in the
file does not change credentials inside existing volumes.

The Docker image build needs network access for Maven, npm and the base
images. `models` pulls `qwen3:4b` and `bge-m3` into the release Ollama volume;
this can take time. If the models are unavailable, normal messaging still
works, while summary/Agent operations return 503.

If the Java image fails while resolving a Maven plugin dependency (for example
`plexus-utils`), first check the earlier `Could not transfer artifact` lines
for the actual network error. The build keeps a Docker BuildKit Maven cache,
but does not reuse the Windows host's `.m2` directory. If Maven Central is
unreachable from Docker, set `IM_MAVEN_MIRROR_URL` in `deploy/.env` to an HTTPS
Maven Central mirror reachable from Docker, for example
`https://maven.aliyun.com/repository/public`, and retry `start`. Keep this
setting out of the project's development Maven configuration. An alternate
mirror cannot fix a broken Docker network or proxy; check the full build log
if it still fails.

Browse `http://127.0.0.1:18080/` (or the configured release port). Registration
is possible **by email only after configuring an SMTP account** in
`deploy/.env` (`IM_SMTP_HOST`, `IM_SMTP_USERNAME`, `IM_SMTP_PASSWORD`,
`IM_SMTP_FROM`; set `IM_SMTP_PORT` for your provider). The SMTP path uses
STARTTLS and finite connect/read/write timeouts. The API never returns the
code outside the `dev` profile. Test registration and password reset using a
disposable mailbox. SMS delivery is not implemented, so phone verification,
registration with a phone number, and phone binding return 503. Do not use the
`dev` profile in this stack.

### Temporary phone testing without SMTP

For this loopback-bound private stack only, add `IM_LOCAL_TEST_CODES=true` to
`deploy/.env` and run `python deploy/manage.py start` again. The user service
returns a generated 6-digit test code to the browser; the frontend fills it
automatically. No SMS or email is sent. Use disposable test contacts. This is
an explicit local testing bypass of contact ownership, not a production phone
verification feature. Before allowing anyone else to access the site, change
the value to `false` and restart. Existing accounts can still log in with
their password; new registrations then require a real delivery provider.

The user container's health endpoint excludes SMTP connectivity, so an unset
or temporarily unavailable mail server cannot prevent messaging from starting.
This does not make email delivery work: configure SMTP and test registration
separately with a disposable mailbox before offering it to users.

Run these commands from `D:\im-assistant\im-backend` to inspect or stop:

```powershell
python .\deploy\manage.py status
docker compose --env-file .\deploy\.env -f .\deploy\compose.yaml logs --tail 100 user chat-a chat-b gateway web
python .\deploy\manage.py stop
```

`stop` preserves every named volume. `smoke` checks the built page, HTTP
routing, WebSocket upgrade, Kafka topic, and pgvector table. It does not create
test accounts, send messages, test SMTP delivery, or verify Agent privacy.
After email registration, manually confirm login, two-account friendship,
direct and group messaging, reconnect sync, private summary and Agent answer
with source citations. The existing probes in `im-backend/tools` target the
development ports and require an explicit endpoint adaptation for this stack.

## Backup and restore drill

```powershell
python .\deploy\manage.py backup
# Use the printed absolute directory, for example:
python .\deploy\manage.py restore-drill .\deploy\backups\20261028T120000Z
```

Backup writes compressed MySQL and pgvector dumps plus SHA-256 checksums under
ignored `deploy/backups`. These files contain private account and message data;
copy them to an encrypted offline store and verify that they can be read there.
The restore drill creates a **new, disposable** Compose project, imports both
dumps, starts the app on the next local web port, runs the routing smoke test,
then removes only its temporary containers and volumes. It does not modify the
live release volumes. Message text is in MySQL; pgvector holds derived vectors.
Redis sessions/rate counters and Kafka offsets are not part of the SQL dump;
users must log in again after a full disaster recovery, and pending realtime
notifications rely on the MySQL outbox for replay. Restore the full stack to
an empty deployment only after verifying a drill and planning a maintenance
window. Keep at least one older backup until the restored app and data are
checked.

## Remaining public deployment decisions

This is one host and one Kafka broker, not a high availability deployment.
Before exposing it to users, configure a domain with HTTPS/WSS at a trusted
reverse proxy, real SMS delivery if phone registration is offered, SMTP sender
verification, secret storage outside Compose environment variables, monitoring
and backup retention. The current password failure limit uses the socket peer
IP; behind a proxy, all clients share its IP bucket until a trusted client-IP
policy is implemented. Do not claim public production readiness before those
items and an actual browser/email/Agent acceptance run pass.
