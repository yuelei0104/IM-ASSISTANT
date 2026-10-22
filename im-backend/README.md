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
`im-common` remains for types genuinely shared across services.

Start the containers above, then in PowerShell from this directory:

```powershell
$env:IM_MYSQL_PASSWORD = Read-Host "IM_MYSQL_PASSWORD from .env"
.\mvnw.cmd -pl im-user-service test
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

Start MySQL and Redis, then keep `im-user-service` running on 8081. In a **new**
PowerShell window from `D:\im-assistant\im-backend`, set the same MySQL password
and start chat on 8082:

```powershell
$env:IM_MYSQL_PASSWORD = Read-Host "IM_MYSQL_PASSWORD from .env"
.\mvnw.cmd -pl im-chat-service test
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
Chat Flyway V5 creates `im_group_mention_notice`. A group message can include
`mentionedUserIds` (up to 10 current members other than the sender). Sending
the same `clientMsgId` again with the same body and mentions returns the
existing message without duplicating notices. The notification inbox is
`GET /api/chats/notices?limit=50&beforeId=123`; read one via
`POST /api/chats/notices/{id}/read`, or all via
`POST /api/chats/notices/read-all`. A `NOTICE` WebSocket event prompts the
browser to reload the authenticated inbox; polling fills gaps after disconnect.
Assistant summaries and tasks remain browser demos.
