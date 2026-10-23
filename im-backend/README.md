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
  It returns HTTP 202 for unknown, self, already-friend and accepted requests
  as well as successfully queued requests. Each user may attempt up to 20
  invitations per day. Lookup endpoints that exposed profiles by phone/handle
  have been removed. The sender's own requests list still shows real pending
  requests, so this is abuse reduction rather than a complete privacy guarantee.
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
Direct-chat discussion hints and tasks remain browser demos.

Chat Flyway V9 adds a `kind` to group messages (`TEXT` by default, `CAPTION`
for explicitly shared speech text). In a discussion room, the microphone
first shows an on-device preview visible only to the speaker. Clicking
**分享字幕到讨论室** sends that reviewed text as a normal persisted group message;
the server requires current membership and returns it through the same
member-scoped history and recovery APIs. It stores text, not microphone
audio. Captions are included in later group summaries when eligible under
the usual join cursor. This is a manual caption-sharing flow, not automatic
continuous meeting transcription; the local browser must support and have
the Chinese speech pack installed.
For a local check, sign in to two existing members in separate browsers, open
the same discussion room, speak into A's microphone, review the preview and
click **分享字幕到讨论室**. B should see a labeled speech-text message; refresh B
to confirm it persists. Start a new room member after the send and confirm
that member cannot read earlier caption history. The summary button uses the
same recent, membership-scoped messages as before. No session-token copying
or Python probe is needed for this check.

### Private group summaries

Chat Flyway V7 creates `im_group_summary`. Group members can generate a
summary from at most 30 messages they are entitled to read and list up to 20
recent summaries at `GET/POST /api/chats/groups/{groupId}/summaries`.
Both actions require a bearer session and current group membership. The SQL
list joins `im_group_member` and returns a summary only when its earliest
source message is newer than that reader's join cursor; newly invited members
cannot read a summary based on older conversation history. Summary text is
stored only in the chat MySQL database and is never cached in the browser.

Generation requires a locally running Ollama with a locally downloaded model.
For example, run `ollama pull qwen3:4b`, then set
`$env:IM_SUMMARY_MODEL = "qwen3:4b"` in the chat service PowerShell window
before starting the service. By default no model is configured and generation
returns 503 with an explanation. The endpoint is fixed to
`127.0.0.1:11434`; `IM_OLLAMA_PORT` may change the local port, but no
remote model URL can be configured. The service sends only the current
member's latest 30 eligible messages (each capped at 500 characters) to that
local endpoint; model failures return a generic error without message text.
Ensure the selected model runs locally rather than using Ollama cloud models.

### Personal tasks

Chat Flyway V8 creates `im_personal_task`. `GET /api/chats/tasks?limit=50&beforeId=123`
returns a cursor page and open/completed totals for the signed-in user.
`POST /api/chats/tasks` accepts `{ "title": "跟进会议" }`; `PATCH /api/chats/tasks/{id}`
accepts `{ "completed": true }`; `DELETE /api/chats/tasks/{id}` removes an item.
All requests require the bearer session; SQL reads and writes are scoped to its
user ID. Tasks are personal, not shared with group members. The feature needs
MySQL and the user service, but does not require an AI model.

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
with the gateway route enabled, then open `http://localhost:5173`:

```powershell
Set-Location D:\im-assistant\im-web
$env:VITE_IM_GATEWAY_URL = 'http://127.0.0.1:8080'
npm run dev
```

Without that environment variable Vite continues proxying straight to 8081
and 8082. The gateway currently has static local targets, not service discovery
or cluster-aware WebSocket routing. Both the gateway and backend ports are for
loopback development; do not expose this setup to the public internet.
