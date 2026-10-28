# Local infrastructure

From `D:\im-assistant\im-backend` in PowerShell:

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up -d
docker compose ps
docker compose exec redis redis-cli ping
docker compose exec mysql mysql -uim_user -p im_assistant
```

Enter `IM_MYSQL_PASSWORD` from `.env` when MySQL prompts for a password.
Redis responds with `PONG`. Leave the MySQL shell with `exit`.

## Repeatable Windows local stack

For the full local web flow (one user service, two chat nodes, cluster gateway,
Vite and the existing Docker infrastructure), use the PowerShell launcher from
`D:\im-assistant\im-backend`. This is a **loopback development deployment**, not
an internet-facing production setup. Install Docker Desktop, Java 21 and Node 22
before starting (Python 3 is needed for the separate probes). Copy `.env.example` to `.env` and set both MySQL
passwords; the launcher reads the app password without printing it.

```powershell
Set-Location D:\im-assistant\im-backend
.\tools\local_stack.ps1 start -Build
.\tools\local_stack.ps1 status
.\tools\local_stack.ps1 smoke
```

`-Build` runs the Maven package/tests and `npm ci`/frontend build before starting
anything. Subsequent runs can use `start` alone when the jars and dependencies
have not changed. Open `http://127.0.0.1:5173/` and register using the
development code shown in the UI. The launcher starts MySQL, Redis and Kafka
with Compose, checks their readiness, creates the notification topic if needed,
then starts 8081, 8082, 8083, 8080 and Vite in that order. It refuses a backend
port already owned on loopback. After Docker and Java are ready it picks the
first free web port from 5173 through 5182 (including Docker Desktop port
forwarding); `-WebPort 5174` requests exactly that port. Use the URL printed by
`start` or `status`. It never kills a process merely because it uses one of these
ports. If an application fails to start, it stops only the application
processes it launched and leaves the Docker containers and their data volumes
intact. If an application starts but the final smoke check fails, the stack
stays running for diagnosis; use `status` and then `stop` when finished.

Database connections for the user and chat services use a UTC MySQL session.
API timestamps without an offset are UTC; the web app renders messages,
requests, notices and tasks in China Standard Time (`Asia/Shanghai`). An
explicit offset in a timestamp takes precedence. Calendar-only task due dates
use the China Standard Time day boundary.

`smoke` checks MySQL, Redis and Kafka, all four Actuator health endpoints, the web page, an expected
unauthenticated JSON response through web → gateway → user service, and a
WebSocket handshakes directly with chat, then through the gateway and web proxy.
The two routed handshakes retry for at most 30 seconds while the gateway checks
the chat nodes; a persistent failure
still reports the failing hop and leaves the processes running. It does not
authenticate a user, send a message, or prove cross-node delivery. Run `cluster_probe.py` and
`kafka_outbox_probe.py` separately for those end-to-end checks. Process IDs and
stdout/stderr logs live in ignored `.local-stack`; `stop` verifies both start
time and command line before stopping a recorded process, so reused PIDs are
not killed. To stop everything started by the script:

```powershell
.\tools\local_stack.ps1 stop
# Also pause the project's MySQL, Redis and Kafka containers (keeps their data):
.\tools\local_stack.ps1 stop -StopInfrastructure
```

Keep local Ollama on Windows if you want summaries/translation: download
`qwen3:4b` or set `$env:IM_SUMMARY_MODEL` to another locally available model
in the same PowerShell session before `start`. The two chat processes inherit
the setting and call 127.0.0.1:11434. If the selected model is absent, normal
messaging still runs and model endpoints return a clear 503. Do not expose the
`dev` verification codes or management endpoints
outside your machine.

For backend applications running directly on Windows, use MySQL at
`127.0.0.1:13307` (database `im_assistant`, user `im_user`) and Redis at
`127.0.0.1:16380`. The MySQL database and user are initialized only when the
MySQL volume is first created; changing `.env` later does not change them.

Run `docker compose stop` to pause these containers, or `docker compose up -d`
to start them again. Named volumes retain data across container restarts.

## User service (local development)

Code is organized inside `im-user-service` by responsibility: `controller`
(HTTP), `dto` (request/response), `service` (auth, codes, sessions), `dao`
(MyBatis method declarations), `resources/mapper` (SQL XML), `model` (user row),
`util` (contact validation), `config`, and `exception`.
`im-common` holds only the shared `{ "userId": ... }` identity contract.

Start the containers above, then in PowerShell from this directory:

```powershell
$env:IM_MYSQL_PASSWORD = Read-Host "IM_MYSQL_PASSWORD from .env"
.\mvnw.cmd -pl im-common install -DskipTests
.\mvnw.cmd -pl im-user-service -am test
.\mvnw.cmd -pl im-user-service spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Flyway creates `im_user` at startup. The `dev` profile returns the six-digit
code as `devCode` from `POST /api/auth/codes` so registration can be tested
without an SMS/email account. **It does not deliver a real SMS or email.**
Without the `dev` profile, code sending returns HTTP 503 until a delivery
provider is implemented; do not use this development flow for public access.
Before issuing registration codes, the web client calls
`POST /api/auth/registration-check` to check field formats only; the public
preflight endpoint does not disclose whether a handle, email or phone exists.
`POST /api/auth/register` checks uniqueness after consuming the contact codes.
Failed password logins are limited in Redis to 5 per account and 30 per socket
peer IP in a 15-minute window. Use a trusted reverse proxy configuration before
relying on the IP limit in production. Codes are verified and consumed by one
atomic Redis script; a consumed code cannot be used by two requests.

Example: register by email, then log in with the password in a second
PowerShell window:

```powershell
$base = 'http://localhost:8081/api/auth'
$contact = 'my-test@example.com'
$code = Invoke-RestMethod -Method Post -Uri "$base/codes" -ContentType 'application/json' -Body (@{purpose='register';contactType='email';contact=$contact} | ConvertTo-Json)
$registration = @{nickname='测试用户';handle='test_user_01';email=$contact;password='Password123';emailCode=$code.devCode} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/register" -ContentType 'application/json' -Body $registration
Invoke-RestMethod -Method Post -Uri "$base/login/password" -ContentType 'application/json' -Body (@{contactType='email';contact=$contact;password='Password123'} | ConvertTo-Json)
```

With both phone and email supplied, request one `register` code for each
contact and include both `phoneCode` and `emailCode` when registering. The
login response contains a bearer token. Send it in `Authorization: Bearer
<token>` to `/api/auth/me`, `/api/auth/bind`, or `/api/auth/logout`. Use
`purpose=bind` when requesting a binding code and include the bearer token.

If a user forgets their password, the web login page provides a recovery flow.
Request a code with `purpose=reset` for an already bound phone or email, then
POST `contactType`, `contact`, `code` and `newPassword` to
`/api/auth/password/reset`. A successful response is HTTP 204. Existing
sessions, including those on other devices, expire after the reset. In local
development the reset code is shown in the UI; production requires an actual
SMS/email provider before this flow can be enabled.

Flyway also creates `im_friend_request` and `im_friendship` with migration V2.
The `Authorization: Bearer <token>` header is required for the following
`/api/contacts` endpoints:

- `GET /?afterId=<id>&limit=50` lists friends ordered by ID, with `items` and
  `nextCursor`; `GET /requests?beforeId=<id>&limit=50` pages requests by ID.
- `POST /requests` accepts `{ "handle": "other_user", "note": "你好" }` or
  `{ "phone": "13800138000", "note": "你好" }`; provide exactly one identifier.
  An unbound phone returns HTTP 404 with `该账号不存在`; an unknown handle,
  self-invitation, existing friendship or duplicate request returns HTTP 202,
  as does a queued invitation. Each authenticated user may attempt up to 20
  invitations per day, including unknown phones. Lookup endpoints that exposed
  profiles by phone/handle have been removed. The sender's own requests list
  still shows real pending requests; revealing unbound phone numbers is a
  deliberate user-facing tradeoff rather than a complete privacy guarantee.
- `POST /requests/{id}/accept`, `/reject`, `/withdraw` handle a pending request.
- `DELETE /{friendId}` removes an established friendship.

Only the recipient may accept or reject; only the sender may withdraw. The
database prevents two simultaneous pending requests for the same pair, even
when both users send at once. The Vue app uses these APIs for the contacts and
friend requests screens. `GET /api/contacts/{friendId}/access` validates an
authenticated friendship for the chat service and returns the caller's user ID.
`GET /api/auth/identity` returns only the authenticated user ID for chat calls;
chat's user-service HTTP client has a 1-second connect and 2-second read timeout.
Stop the service with Ctrl+C in its terminal.

## One-to-one messages (local development)

Search within an authenticated conversation using
`GET /api/chats/direct/{friendId}/messages/search?q=keyword&limit=20&beforeId=123`
or `GET /api/chats/groups/{groupId}/messages/search` with the same parameters.
The query is 2–50 characters; `%`, `_`, and `=` are treated literally. Search
is paged newest first, never spans conversations, and group results are limited
to messages newer than the caller's join cursor. The query uses the existing
conversation history index and a substring `LIKE` filter; for very large
conversations, a dedicated search index will be needed.

Start MySQL and Redis, then keep `im-user-service` running on 8081. In a **new**
PowerShell window from `D:\im-assistant\im-backend`, set the same MySQL password
and start chat on 8082:

```powershell
$env:IM_MYSQL_PASSWORD = Read-Host "IM_MYSQL_PASSWORD from .env"
.\mvnw.cmd -pl im-chat-service -am test
.\mvnw.cmd -pl im-chat-service spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Chat uses a separate `im_chat_schema_history` Flyway table and creates
`im_direct_message` automatically. The user service must already have applied
its V1/V2 migrations. Both services use the same local `im_assistant` database
for this development stage; chat never reads the user service's tables directly.
Because that database is already nonempty, the chat service baselines its own
history at version 0 on first startup, then runs its V1 message migration.
The user service's Flyway history and tables are untouched. Do not remove
`baseline-version=0`: the default version 1 would skip the chat V1 migration.
Each chat request forwards its bearer token to the user service to establish
the caller's identity. Sending also verifies the pair is **currently** friends.

- `POST /api/chats/direct/{friendId}/messages` accepts
  `{ "clientMsgId": "UUID", "body": "你好" }`. Repeating the same UUID and body
  returns the existing message; reusing the UUID for another body or recipient
  returns 409. Maximum body length is 4,000 characters.
- `GET /api/chats/direct/{friendId}/messages?limit=50&beforeId=123` returns
  `{ "messages": [ ... ], "nextBeforeId": 123 }`, with messages in ascending ID
  order. Omit `beforeId` for the latest page; pass `nextBeforeId` to load older
  messages until it is null. A logged-in participant can read history after the
  friendship ends, but cannot send more messages.
- `GET /api/chats/direct/{friendId}/messages/sync?afterId=123&limit=50`
  returns ascending messages after the last applied ID and a `nextAfterId`
  when another page exists. The client repeats until `nextAfterId` is null,
  advancing its cursor only after applying each page. A newly opened chat
  starts with the latest history page; older messages remain available via
  `beforeId`.
- `GET /api/chats/direct/unread` returns unread counts per sender for the
  authenticated user. `POST /api/chats/direct/{friendId}/read` accepts
  `{ "lastReadMessageId": 123 }` and returns HTTP 204. The message must have
  been received from that friend; the stored read cursor only moves forward.
  Chat Flyway V2 adds the `im_direct_read` table and recipient lookup index.

The sender can query `GET /api/chats/direct/{peerId}/receipt` for
`{ "lastReadMessageId": 123 }`. This is the peer's durable read cursor for
messages received from this sender. It starts at 0, never moves backward and
only advances when the peer marks a message actually received in this pair.
An advancement publishes an ID-only `READ` event to both authenticated users'
WebSocket sessions; reconnect and periodic refresh query MySQL again. The web
client shows **已发送** until the peer's cursor reaches that direct message, then
**对方已读**. It does not claim delivery to the peer's device and does not apply
to group messages or recalled messages. No database migration is required.

To check locally after restarting the chat service, with user-service in its
dev profile and MySQL/Redis running:

```powershell
Set-Location D:\im-assistant\im-backend
python .\tools\direct_read_receipt_probe.py
```

### Message recall

Chat Flyway V10 adds direct and group recall timestamps and change feeds.
Within two minutes of sending, a message's sender can use
`DELETE /api/chats/direct/{friendId}/messages/{messageId}` or
`DELETE /api/chats/groups/{groupId}/messages/{messageId}`. Group recall also
requires current membership. The server clears the stored body, keeps a
timestamped tombstone in history, and removes it from search and unread counts.
Group recall also removes its mention notices and any saved summary spanning
that message. Repeating a successful recall returns 204; expired or invalid
requests fail. Reusing a recalled message's client UUID cannot restore its text.

Clients retrieve missed recalls with `GET` on the corresponding
`/messages/recalls?afterId=0&limit=50` endpoint. The feed has `items` and
`nextAfterId`; group access follows the current membership and join cursor.
The web client merges this feed with history during reconnect. Recall cannot
erase text already copied or captured outside the application.

After the Java 21 build and service restart, check against two disposable
accounts on the local dev database (no Ollama required):

```powershell
Set-Location D:\im-assistant
python .\im-backend\tools\message_recall_probe.py
```

Pass `--check-summary` only when the local model is running to verify that
a saved summary containing a recalled group message is removed.

Run `npm run dev` from `D:\im-assistant\im-web` after restarting Vite so its
proxy routes `/api/chats` to 8082. Test with two registered accounts in separate
browser profiles: accept a friend request, open the friend in 联系人 → 发消息,
send from A, then open that friend on B. B receives a WebSocket notice and
reloads the active conversation. Reload each page to confirm history
comes from MySQL, and try the same message UUID twice to check idempotency.
While B views another conversation, send two messages from A: B's sidebar
should show `2` beside A promptly (or within five seconds during a WebSocket outage). Open A's chat and wait for the
messages to load; the badge should disappear. Reload B to confirm the read
state persists, and repeat from a separate browser profile for multi-device
behavior. The app receives realtime message notifications on `/api/chats/ws`.
The browser authenticates by sending `AUTH <bearer token>` after connection
(never in the WebSocket URL), refreshes its authentication every five minutes,
and reconnects with exponential backoff. After reconnect it fetches unread
counts and the active conversation. Every 30 seconds it also performs a
recovery poll while the socket is connected, or every five seconds while
disconnected, because Redis Pub/Sub notifications are not durable.
The server publishes IDs only after the MySQL message transaction commits;
the recipient fetches authenticated message history over HTTP. Configure
`IM_WS_ALLOWED_ORIGINS` for a deployed frontend origin. The chat service also
requires the same Redis host and port as the user service.
Migration V3 backfills a per-recipient, per-peer inbox index. New sends insert
the inbox row in the same database transaction as the message, and unread
queries count only the indexed unread range for each peer.
Previously opened friend conversations keep a per-account browser entry after
removal, so either participant can still reopen their history on that browser.
Group chats are persisted in MySQL (chat Flyway V4), with membership checks,
50-member capacity, cursor-based history and unread counts. Inviting a new
member sets their history cursor to the latest committed group message.
Members can leave with `DELETE /api/chats/groups/{groupId}/members/me` and the
owner can remove another member with `DELETE /api/chats/groups/{groupId}/members/{memberId}`.
Both return HTTP 204, lock the group row against concurrent sends/invites, and
notify the former member to clear cached group content. The owner currently
cannot leave or be removed. A former member cannot load messages, summaries,
or mention text. On re-invitation, the new join cursor hides messages and
mentions from the earlier membership period; no group messages are deleted.
The same forward recovery endpoint is available at
`GET /api/chats/groups/{groupId}/messages/sync?afterId=123&limit=50`;
it enforces membership and the member's join cursor. Flyway V6 creates a
per-pair lock table for direct sends, so concurrent writes to one conversation
commit in ID order. Group sends already serialize on the group row. The
forward cursor is saved per account and conversation in browser storage.
Reopening a conversation loads its latest history page, then recovers forward
from the saved cursor. A new browser starts at the latest page and can page
back through older messages.
Chat Flyway V5 creates `im_group_mention_notice`. A group message can include
`mentionedUserIds` (up to 10 current members other than the sender). Sending
the same `clientMsgId` again with the same body and mentions returns the
existing message without duplicating notices. The notification inbox is
`GET /api/chats/notices?limit=50&beforeId=123`; read one via
`POST /api/chats/notices/{id}/read`, or all via
`POST /api/chats/notices/read-all`. A `NOTICE` WebSocket event prompts the
browser to reload the authenticated inbox; polling fills gaps after disconnect.

Chat Flyway V11 adds personal group keyword subscriptions and a notice kind.
`GET /api/chats/notices/keywords` lists the current account's words;
`PUT /api/chats/notices/keywords` with `{ "words": ["会议", "截止"] }`
atomically replaces them. Use up to five distinct words of 2–32 characters
each; an empty list clears subscriptions. Only new group messages and reviewed
captions can trigger these alerts. The group sender and outsiders never receive
one. A message matching multiple words creates one `KEYWORD` notice, while an
explicit @mention takes precedence and produces one `MENTION` notice. Both
types share the authenticated paginated inbox and read actions. Group removal,
re-invitation and recall use the existing visibility cursor and cleanup; saving
words does not search or notify about earlier messages. No model is needed.

After restarting chat-service on the dev profile, check with disposable local
accounts:

```powershell
Set-Location D:\im-assistant\im-backend
python .\tools\group_keyword_probe.py
```
Direct-chat discussion hints remain browser demos.

Chat Flyway V9 adds a `kind` to group messages (`TEXT` by default, `CAPTION`
for explicitly shared speech text). In a discussion room, the microphone
first shows an on-device preview visible only to the speaker. Clicking
**分享字幕到讨论室** sends that reviewed text as a normal persisted group message.
For a remote-control session without a microphone, type a caption in the
discussion room composer and click **作为字幕发送**; this checks the server flow,
but does not validate speech recognition. Both paths require an explicit click;
the server requires current membership and returns it through the same
member-scoped history and recovery APIs. It stores text, not microphone
audio. Captions are included in later group summaries when eligible under
the usual join cursor. This is a manual caption-sharing flow, not automatic
continuous meeting transcription; the local browser must support and have
the Chinese speech pack installed.
For a local check, sign in to two existing members in separate browsers, open
the same discussion room, use A's microphone preview or type a caption and
click the corresponding share action. B should see a labeled caption; refresh B
to confirm it persists. Start a new room member after the send and confirm
that member cannot read earlier caption history. The summary button uses the
same recent, membership-scoped messages as before. No session-token copying
or Python probe is needed for this check.

For a repeatable API-level privacy check without copying session tokens, run
the probe from the repository root after starting both services with the
`dev` profile. It creates three disposable accounts and a temporary group,
then dissolves the group; no microphone or Ollama installation is needed:

```powershell
Set-Location D:\im-assistant
python .\im-backend\tools\group_privacy_probe.py
```

The probe checks outsider access to send, history, sync, search, direct message
lookup and summaries; it then invites a new member and checks that pre-join
captions stay hidden while post-join captions remain visible. If Ollama and
`IM_SUMMARY_MODEL` are running, add `--check-summary` to also generate a
summary before the invitation and verify that the newcomer cannot list it.
It also verifies leaving, removal, transfer to a current member and dissolution
by the new owner. The original owner remains a member until leaving; after
dissolution the group and its messages, mention notices, summaries and
memberships are deleted in one transaction. `POST /api/chats/groups/{id}/owner`
accepts `{ "memberId": 123 }` from the current owner; `DELETE /api/chats/groups/{id}`
requires the current owner. Other members receive a refresh event that removes
the room from their UI. A sole owner can dissolve directly.
The script sends requests only to loopback ports 8081 and 8082, and keeps
temporary bearer tokens in memory. A passing probe does not validate browser
speech recognition, which needs a microphone.

### Private group summaries

Chat Flyway V7 creates `im_group_summary`. Group members can generate a
summary from at most 30 messages they are entitled to read and list up to 20
recent summaries at `GET/POST /api/chats/groups/{groupId}/summaries`.
Both actions require a bearer session and current group membership. The SQL
list joins `im_group_member` and returns a summary only when its earliest
source message is newer than that reader's join cursor; newly invited members
cannot read a summary based on older conversation history. Summary text is
stored only in the chat MySQL database and is never cached in the browser.
The summary insert also checks membership and the join cursor in the same SQL
statement, covering a membership change after the local model returns.

Generation requires a locally running Ollama with a locally downloaded model.
For example, run `ollama pull qwen3:4b`. By default the summary uses
`IM_SUMMARY_MODEL`, then `IM_AGENT_MODEL`, then `qwen3:4b`. Set an override
in the chat-service PowerShell window before starting the service. The
endpoint is fixed to
`127.0.0.1:11434`; `IM_OLLAMA_PORT` may change the local port, but no
remote model URL can be configured. The service sends only the current
member's latest 30 eligible messages (each capped at 500 characters) to that
local endpoint; model failures return a generic error without message text.
Ensure the selected model runs locally rather than using Ollama cloud models.
The summary prompt asks for only three concise fields: topic, decision and
action item. A field without a clear basis becomes `未提及`. Model self-analysis
and unstructured text are discarded; for missing fields, the server can quote
only an explicit `议题：` / `结论：` / `待办：` line from a message in that caller's
authorized source set. It does not infer unlabelled facts from keyword matches.
Users can inspect the original messages to check omissions or unsupported claims.

To check a generated summary against its inputs, a current group member can
request `GET /api/chats/groups/{groupId}/summaries/{summaryId}/sources`.
It returns up to 30 source messages in chronological order, with no-cache
headers and no client message IDs. SQL verifies current group membership,
the summary's first-message join cursor and each message's live status on
every read. A member who joined after the summary receives 404; outsiders
receive 403. The web view offers **核对原文**, showing the messages next to the
summary for human review. This lets people spot omissions or unsupported
claims; it does not automatically certify the model's accuracy. Run
`python .\tools\group_privacy_probe.py --check-summary` with a local model
after restarting chat-service to verify source visibility as well.

### Local LangChain4j group Agent (opt-in)

This is a bounded, private group-message RAG workflow, not an enterprise
knowledge base. `POST /api/chats/groups/{groupId}/agent/ask` accepts
`{"question":"下次会议需要准备什么？"}` and returns an answer plus the group
message IDs and text used as checkable sources. The endpoint requires the
caller's existing bearer session and group membership. It loads at most 30
messages newer than that member's join cursor, excludes recalled messages,
and truncates each input to 500 characters. It rechecks membership, join cursor
and live source visibility **after** inference. The response is not stored and
uses `Cache-Control: no-store`.

LangChain4j embeds the permitted messages and question using a local Ollama
embedding model, performs top-three vector retrieval in a **request-local**
`InMemoryEmbeddingStore`, then uses a LangChain4j AI Service to answer. A
single `@Tool` allows the model to inspect a message from that same authorized
snapshot by ID. The tool has no write operations or access to other groups.
Short acknowledgements are skipped; the response is limited to a concise answer
and only the IDs the model cites from the authorized context. If the context
does not answer the question, it returns a fixed insufficient-evidence answer
with no citations. All messages sent to the model or inspected by the tool
are rechecked after inference before returning a supported answer.
When one short message explicitly matches the question but a small model
omits the required citation format, the service can quote that exact message
with its verified ID. It does not make this fallback for unrelated messages.
There is no persistent or shared vector index, no PGVector/Milvus, no AI Kafka
queue, no autonomous task creation, and no cross-request chat memory. Building
the vectors again on each question makes this a small-group prototype rather
than a large-corpus search service. Group text stays on local Ollama.

To enable the feature, download a local embedding model in addition to the
chat model, then stop and restart chat-service. With `local_stack.ps1`, set
the variables in the **same PowerShell session** before `start -Build`:

```powershell
ollama pull bge-m3
Set-Location D:\im-assistant\im-backend
.\tools\local_stack.ps1 stop
$env:IM_SUMMARY_MODEL = 'qwen3:4b'
$env:IM_AGENT_MODEL = 'qwen3:4b'
$env:IM_AGENT_EMBED_MODEL = 'bge-m3'
.\tools\local_stack.ps1 start -Build
```

For a manually started chat-service, set the same variables in each chat node's
PowerShell window **before** starting it. If the embedding model or chat model
is missing, the Agent endpoint returns 503; normal messaging still works.
`IM_AGENT_MODEL` defaults to `IM_SUMMARY_MODEL` and then `qwen3:4b`;
`IM_AGENT_EMBED_MODEL` must still name a locally installed embedding model.
Ask from a real discussion room in the right panel. Task suggestions are only
text: the user must review and explicitly create any private task. Verify with
two members and a later-invited account; the latter must not receive old message
sources. Revoking membership or recalling a source during inference rejects the
result. Run `python .\tools\group_privacy_probe.py --check-agent` against the
running dev stack to verify outsider denial, pre-join exclusion, post-join
retrieval, removal and rejoin boundaries with real local models. It creates
three disposable accounts and dissolves the test group. Browser review of
answer quality is still required.

#### Agent end-to-end acceptance on the local development stack

After `local_stack.ps1 start -Build` reports all services ready and both Ollama
models are installed, run from `D:\im-assistant\im-backend`:

```powershell
python .\tools\agent_acceptance.py
```

The script creates two disposable friends and a group, then verifies an
explicit topic/decision/task summary and its exact source messages, a factual
answer with the one supporting message, an unknown question with no citations,
rejection of a malicious instruction embedded in a group message, and removal
of recalled facts from the Agent and saved summaries. It then runs the
three-account membership/privacy probe with both `--check-summary` and
`--check-agent`. Every asserted check must print `PASS`; any `FAIL` is an
acceptance failure. If testing model quality repeatedly, use `--quality-only`
to skip the longer membership probe. Test accounts and messages remain in the
local development database; temporary groups are dissolved.

The checks use a small synthetic fixture and cannot prove that every natural
language summary is accurate. For final human review, ask a real group question
whose answer is in one message, one with no answer, and inspect every cited
message in the browser. Do not mark the feature accepted when a model returns
an unsupported claim, even if HTTP requests and Maven tests pass. The local
probe requires dev registration codes and must not target production.

### Private group translation preview

Group messages also offer an optional, private translation preview at
`POST /api/chats/groups/{groupId}/messages/{messageId}/translation` with
`{"targetLanguage":"en"}` or `"zh"`. Only a current member who can see the
original, non-recalled text or caption can request it. Messages before their
join cursor are hidden. The server checks access both before and after the
local model responds, in case membership or recall changes during inference.
The response is `Cache-Control: no-store`; the translation is not written to
MySQL or sent to the group. The web client lets the user edit the preview and
explicitly put it into the composer for review before sending. It is not
automatically posted. The same local `IM_SUMMARY_MODEL` and loopback Ollama
configuration described above is used; without it the endpoint returns 503.
After restarting chat-service, check access and the preview flow with:

```powershell
Set-Location D:\im-assistant\im-backend
python .\tools\group_translation_probe.py
```

This probe creates three disposable accounts and calls the local model once.
It verifies the preview shape and privacy boundaries, but a person should
still review the quality of the translated text in the browser.

### Personal tasks

Chat Flyway V8 creates `im_personal_task`. `GET /api/chats/tasks?limit=50&beforeId=123`
returns a cursor page and open/completed totals for the signed-in user.
`POST /api/chats/tasks` accepts `{ "title": "跟进会议" }`; `PATCH /api/chats/tasks/{id}`
accepts `{ "completed": true }`; `DELETE /api/chats/tasks/{id}` removes an item.
All requests require the bearer session; SQL reads and writes are scoped to its
user ID. Tasks are personal, not shared with group members. The feature needs
MySQL and the user service, but does not require an AI model.

Flyway V12 lets a person explicitly turn a currently visible group message
into a personal task. `POST /api/chats/tasks` may include `sourceGroupId` and
`sourceMessageId` alongside the reviewed, editable `title`; both source IDs
must be supplied together. The server locks the group and verifies current
membership, join cursor and message recall status before saving. It never
generates or saves a task automatically. Task listings include the source IDs
only while that person can still see the live group message; removal, leaving,
re-invitation past the old message, recall and dissolution hide the link. The
user-confirmed title remains a private note after those changes; it may contain
text the user explicitly chose to copy from the message. Source message bodies
are not stored as a separate task field. The browser rechecks access
when the user clicks **查看来源**. This works while Ollama is downloading:

```powershell
Set-Location D:\im-assistant\im-backend
python .\tools\message_task_probe.py
```

Flyway V13 adds an optional `due_date` to personal tasks. Create accepts an ISO
date such as `"dueDate": "2026-11-30"`; `PUT /api/chats/tasks/{id}/details`
accepts `{ "title": "确认上线时间", "dueDate": null }` to edit the title and
clear the date. Updates use the signed-in owner's ID in the SQL WHERE clause;
other accounts receive 404. Dates are calendar dates in the user's browser,
and overdue status is shown in the task list without background reminders.
After restarting chat-service on the updated code, check with:

```powershell
Set-Location D:\im-assistant\im-backend
python .\tools\task_due_date_probe.py
```

## Optional local gateway

`im-gateway` is a runnable Spring Cloud Gateway on `127.0.0.1:8080`. It routes
`/api/auth/**` and `/api/contacts/**` to 8081, `/api/chats/**` HTTP and
`/api/chats/ws` WebSocket to 8082. Start both services above first. In a third
PowerShell window from `D:\im-assistant\im-backend`:

```powershell
.\mvnw.cmd -pl im-gateway -am package
java -jar .\im-gateway\target\im-gateway-0.0.1-SNAPSHOT.jar
```

Check `http://127.0.0.1:8080/actuator/health`. In a fourth window start Vite
with the gateway route enabled, then open the `Local` URL printed by Vite
(normally `http://localhost:5173`, or 5174 if 5173 is occupied):

```powershell
Set-Location D:\im-assistant\im-web
$env:VITE_IM_GATEWAY_URL = 'http://127.0.0.1:8080'
npm run dev
```

After rebuilding and restarting chat-service, it also exposes
`http://127.0.0.1:8082/actuator/health`. Check the three backend processes in
a separate PowerShell window without closing their startup windows:

```powershell
Get-NetTCPConnection -LocalPort 8080,8081,8082 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalAddress,LocalPort,OwningProcess
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
Invoke-RestMethod http://127.0.0.1:8082/actuator/health
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

An `UP` response from a backend confirms that process is running; it does not
replace an end-to-end HTTP or WebSocket check. GitHub Actions runs the complete
Maven unit tests, frontend tests, and frontend build on pull requests and
pushes to `main`. Locally, run the same checks from their respective folders:

```powershell
Set-Location D:\im-assistant\im-backend
.\mvnw.cmd test
Set-Location D:\im-assistant\im-web
npm ci
npm test
npm run build
```

In the `dev` profile, user-service HTTP and chat-service WebSocket accept
loopback browser origins on any local port, so Vite can choose the next free
port. These services still bind to 127.0.0.1 for local development.

Without that environment variable Vite continues proxying straight to 8081
and 8082. The default gateway profile has static single-node targets; the
optional local cluster profile below supplies two fixed chat nodes. Both
profiles bind to loopback; do not expose this setup to the public internet.

### Two-node chat cluster (opt-in local profile)

The default gateway profile above remains a single-node route. To exercise two
chat nodes, start a single user-service on 8081 and two copies of the same
chat-service jar on 8082 and 8083. They must share the **same MySQL database
and Redis**. Each node keeps only its own authenticated WebSocket sessions;
after a database commit, Redis Pub/Sub carries ID-only notifications to both
nodes. A client always fetches the real message through authenticated HTTP.
Pub/Sub is best-effort, so reconnection synchronizes missing messages from
MySQL by cursor. There is no separate Redis user-to-node routing table.

Build once from `D:\im-assistant\im-backend`:

```powershell
.\mvnw.cmd -pl im-chat-service,im-gateway -am package
```

Open **two separate** PowerShell windows in this directory. In each one run
`$env:IM_MYSQL_PASSWORD = Read-Host 'IM_MYSQL_PASSWORD from .env'`, then run one
of these commands per window:

```powershell
java -jar .\im-chat-service\target\im-chat-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --server.port=8082
```

```powershell
java -jar .\im-chat-service\target\im-chat-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --server.port=8083
```

Stop the old gateway on 8080 if it is running. Start the new gateway jar in a
third window with its `cluster` profile:

```powershell
java -jar .\im-gateway\target\im-gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=cluster
```

This profile supplies the two loopback chat instances directly to the gateway's
chat load balancer. It routes both HTTP and WebSocket handshakes across healthy
nodes, checking `/actuator/health` and refreshing the result after three seconds. The
WebSocket stays attached to its chosen node for the life of that connection;
HTTP requests may use either node because they share MySQL. No write request
is automatically retried by the gateway. You can override the instance URIs
using `IM_CHAT_NODE_A_URL` and `IM_CHAT_NODE_B_URL` on the gateway process;
keep them on loopback in this local setup. Point Vite at the gateway with
`$env:VITE_IM_GATEWAY_URL = 'http://127.0.0.1:8080'` before `npm run dev`.

With both chat nodes up and user-service in `dev` mode, run from the backend
directory:

```powershell
python .\tools\cluster_probe.py
```

The probe opens authenticated sockets on both nodes and the gateway, sends
in both directions, then verifies that MySQL sync recovers a message sent
while a socket was disconnected. It creates two disposable accounts and three
messages. For a gateway failover check, stop **one** chat process with Ctrl+C,
leave the other running, wait at least five seconds for the health result to expire,
and run:

```powershell
python .\tools\cluster_probe.py --gateway-only
```

This verifies repeated HTTP requests and a fresh authenticated WebSocket
through the surviving node. Restart the stopped node after the check. Existing
connections on a stopped process necessarily close; the browser reconnects
and fetches missed data. This profile is a local two-node demonstration, not
a production service registry or an exactly-once push guarantee.

### Optional Kafka notification outbox

The default chat mode above still uses MySQL plus Redis Pub/Sub and does not
need Kafka. The opt-in Kafka mode adds Flyway V14's `im_notification_outbox`.
The same transaction writes a direct/group message, recall, read cursor or
notice and its **ID-only** notification row. A single MySQL advisory lock
serializes the outbox relay across chat nodes: it publishes pending rows in ID
order to Kafka, waits for broker acknowledgement, then marks each row sent.
On a crash or ambiguous timeout the row may be sent again. The Kafka consumer
group republishes to Redis, then manually commits its offset. If Redis fails,
the listener retries rather than acknowledging. Every WebSocket node receives
Redis's ID-only fanout; clients still fetch authenticated MySQL history and
recover gaps by cursor. These are **at-least-once notifications**, not
exactly-once delivery to a browser. Only rows marked published more than seven
days ago are cleaned up. Kafka holds no message bodies or bearer tokens.

The Compose Kafka broker is a single local KRaft node with replication factor
one. It is for outage/retry testing, not broker high availability. Start it
only when testing Kafka, from `D:\im-assistant\im-backend`. The optional
`kafka-volume-init` service first assigns the persistent Kafka directory to
the image's UID/GID 1000; this also repairs an existing root-owned volume
without deleting its contents. Do not use `docker compose down -v` to fix a
permission error: that would remove the project's MySQL and Redis volumes.

```powershell
docker compose --profile kafka up -d kafka
docker compose --profile kafka ps --all
docker compose --profile kafka exec kafka /opt/kafka/bin/kafka-topics.sh `
  --bootstrap-server localhost:19092 --create --if-not-exists `
  --topic im.chat.notifications.v1 --partitions 6 --replication-factor 1
docker compose --profile kafka exec kafka /opt/kafka/bin/kafka-topics.sh `
  --bootstrap-server localhost:19092 --describe --topic im.chat.notifications.v1
.\mvnw.cmd -pl im-chat-service -am test
.\mvnw.cmd -pl im-chat-service -am package
```

Stop both old chat instances and restart them from the newly built jar, each
in its own PowerShell window. Set the MySQL password and `IM_KAFKA_ENABLED` in
**each** window; port 8082 in the first and 8083 in the second:

```powershell
$env:IM_MYSQL_PASSWORD = Read-Host 'IM_MYSQL_PASSWORD from .env'
$env:IM_KAFKA_ENABLED = 'true'
java -jar .\im-chat-service\target\im-chat-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --server.port=8082
```

```powershell
$env:IM_MYSQL_PASSWORD = Read-Host 'IM_MYSQL_PASSWORD from .env'
$env:IM_KAFKA_ENABLED = 'true'
java -jar .\im-chat-service\target\im-chat-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --server.port=8083
```

Keep user-service on 8081 and the `cluster` gateway on 8080. Then check both
normal cross-node delivery and a temporary Kafka outage from this directory:

```powershell
python .\tools\cluster_probe.py
python .\tools\kafka_outbox_probe.py
```

The second script stops only this Compose project's Kafka container after a
baseline send, verifies that another send still commits and has a pending
outbox row, restarts Kafka in a `finally` block, and waits for the recipient's
WebSocket notice. It requires Docker Compose and the user service's `dev`
profile; it creates two disposable accounts and two messages. The script
resolves Compose relative to its own location, so it can also be invoked as
`python .\im-backend\tools\kafka_outbox_probe.py` from the repository root.
It reports a missing baseline outbox separately from a relay stall. Avoid running
other Kafka outage checks at the same time. If an external process kills the
script during the broker pause, restore with
`docker compose --profile kafka start kafka`.

With the `dev` profile and Kafka mode on, each chat node exposes three local
Actuator metrics. Run these from PowerShell while both chat instances are up:

```powershell
foreach ($port in 8082,8083) {
  foreach ($name in 'im.chat.outbox.pending','im.chat.outbox.oldest.seconds',
                    'im.chat.outbox.relay.failures') {
    $metric = Invoke-RestMethod "http://127.0.0.1:$port/actuator/metrics/$name"
    "$port $name = $($metric.measurements[0].value)"
  }
}
```

The pending count and oldest age read the same MySQL table on both nodes;
relay failure counters belong to each node and reset when it restarts. For a
local warning threshold, inspect a sustained oldest age above 60 seconds or
pending count above 100 after Kafka is healthy. An age reported as `NaN`
means the metric could not query MySQL. These metrics are exposed only by the
local `dev` profile; configure a protected management endpoint separately
for other environments.

Kafka is not used as a store for the actual message or a durable device
delivery receipt. If the recipient has no live WebSocket, Redis Pub/Sub can
still drop the hint; on reconnect the browser reads its MySQL cursor. The
outbox does not backfill notifications from messages sent before Kafka mode
was enabled. Start all chat instances in the same mode to avoid duplicate
notifications through the old direct Redis path.

### Bounded message baseline and broker recovery report

Keep the local user service in `dev` mode, both Kafka-enabled chat nodes,
gateway `cluster` profile, Docker MySQL/Redis and the Kafka broker running.
Run from `D:\im-assistant\im-backend` (Python 3 standard library only):

```powershell
python .\tools\message_benchmark.py --sizes 100,500,1000 --concurrency 5
python .\tools\message_benchmark.py --sizes 100,500,1000 --concurrency 20
```

Each invocation registers four disposable accounts as two friend pairs; it
sends the listed **total** number of direct messages per stage through the
gateway and exhausts every 50-row receiver sync page. The report compares
client message IDs, bodies, sender/recipient IDs and returned server IDs to
find missing, duplicate, unexpected or mismatched rows. It records successful
HTTP acknowledgement throughput and nearest-rank p50/p95/p99 latency,
along with the two nodes' outbox metrics before and after. A nonzero exit
means an acceptance failure; after each stage, the script stops before
the next size if correctness fails. JSON reports go to the ignored
`benchmark-results` directory and never contain tokens, passwords or
message text. Outbox metrics cover the entire development database, so run
without unrelated chat traffic when comparing stages. Records and accounts
remain in the **development** database.

To also measure a bounded Kafka outage and the time to drain that run's
outbox rows after restart, add `--outage-count 20`. This option temporarily
stops **only this Compose project's Kafka container** and starts it again in
a `finally` block; do not run it against shared infrastructure. It does not
measure WebSocket delivery latency. This is a local few-conversation contention
baseline, not peak cluster capacity; report the hardware, sizes, concurrency,
success count and latency together when discussing results.
