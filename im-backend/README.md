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
`POST /api/auth/registration-check` to check profile fields and contact
uniqueness. `POST /api/auth/register` repeats these checks when saving to
handle another registration taking the same handle or contact meanwhile.

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

- `GET /users/{handle}` looks up a public profile by exact handle.
- `GET /` lists friends; `GET /requests` lists up to 100 recent or pending requests.
- `POST /requests` accepts `{ "handle": "other_user", "note": "你好" }`.
- `POST /requests/{id}/accept`, `/reject`, `/withdraw` handle a pending request.
- `DELETE /{friendId}` removes an established friendship.

Only the recipient may accept or reject; only the sender may withdraw. The
database prevents two simultaneous pending requests for the same pair, even
when both users send at once. The Vue app uses these APIs for the contacts and
friend requests screens. Conversations still use local demonstration data.
Stop the service with Ctrl+C in its terminal.
