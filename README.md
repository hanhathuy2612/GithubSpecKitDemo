# Reset Password Demo

A Spec Kit demo project: "Forgot password, reset via email", built with Java 21 and Spring Boot 4.

- A user enters their email and receives a reset link.
- The link expires after **15 minutes** and works **once**.
- Unknown, disabled and rate-limited emails get **the same response** as real ones, so nobody can probe which accounts exist.

The project follows the [constitution](.specify/memory/constitution.md): thin controllers, business logic in services, automated tests for every endpoint, and no plain-text tokens or passwords.

## Documents

Each feature keeps a few living documents: the spec (what and why), the plan (stack, constitution check, design summary) and the research (decisions). Field-level details live in the code and tests.

| Feature | Spec | Plan and decisions | Implementation log |
|---|---|---|---|
| 001 Reset password | [spec.md](specs/001-reset-password/spec.md) | [plan.md](specs/001-reset-password/plan.md), [research.md](specs/001-reset-password/research.md) | [tasks.md](specs/001-reset-password/tasks.md) (frozen) |
| 002 User auth (sign up, sign in, sign out, change password) | [spec.md](specs/002-user-auth/spec.md) | [plan.md](specs/002-user-auth/plan.md), [research.md](specs/002-user-auth/research.md) | [tasks.md](specs/002-user-auth/tasks.md) (frozen) |

## Quick start

```bash
./gradlew test                         # unit and endpoint tests (in-memory H2, fake mail sender)
./gradlew test -PincludeTags=timing    # SC-004 timing check (also a separate CI job)

docker compose up -d mailpit           # local SMTP :1025, inbox UI at http://localhost:8025
./gradlew bootRun                      # app at http://localhost:8080/forgot-password
```

Demo accounts: `alice@example.com` and `bob@example.com` (active), `disabled@example.com` (disabled). Their initial password is `demo-password-1`.

## Manual end-to-end check (001)

With Mailpit and `bootRun` running:

1. Open `http://localhost:8080/forgot-password` and submit `alice@example.com`. **Expect**: the generic confirmation message.
2. Open the Mailpit UI. **Expect**: one "Reset your password" email with a link.
3. Submit `nobody@example.com`. **Expect**: the same page as step 1, and no new email.
4. Click the link and set a new password (8–64 characters) twice. **Expect**: "Your password has been reset", plus a "Your password was changed" email.
5. Click the same link again. **Expect**: "This link is invalid or has expired."
6. Request 4 links for `bob@example.com` within 15 minutes. **Expect**: 4 identical pages, but only 3 emails.
7. Check the application log. **Expect**: only `password_reset.*` events with account ids, and no token, password or email.

## Manual end-to-end check (002)

```bash
curl -s -XPOST localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{"email":"carol@example.com"}'
# open the "Finish creating your account" link from Mailpit and choose a password
curl -s -XPOST localhost:8080/api/auth/sign-in -H 'Content-Type: application/json' \
     -d '{"email":"carol@example.com","password":"<chosen>"}'          # -> accessToken, refreshToken
curl -s localhost:8080/api/auth/me -H "Authorization: Bearer <accessToken>"
curl -s -XPOST localhost:8080/api/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"<refreshToken>"}'
curl -s -XPOST localhost:8080/api/auth/password -H "Authorization: Bearer <accessToken>" -H 'Content-Type: application/json' \
     -d '{"currentPassword":"<chosen>","newPassword":"<new>","confirmPassword":"<new>"}'   # old tokens stop working
curl -s -XPOST localhost:8080/api/auth/sign-out -H 'Content-Type: application/json' -d '{"refreshToken":"<refreshToken>"}'
```

## Endpoints

| Method and path | Purpose |
|---|---|
| `POST /api/password-reset/requests` `{"email"}` | Request a link. Always `202` with the same message |
| `POST /api/password-reset/confirm` `{"token","newPassword","confirmPassword"}` | Set a new password. `200`, or `400` with Problem Details |
| `GET/POST /forgot-password` | Page for requesting a link |
| `GET/POST /reset-password?token=…` | Page for setting a new password. Opening the link never consumes it. A successful reset also signs the account out everywhere (002) |
| `POST /api/auth/register` `{"email"}` | Start registration. Always `202` with the same message; the password is chosen from the emailed link |
| `GET/POST /complete-registration?token=…` | Page for choosing the password; creates the account |
| `POST /api/auth/sign-in` `{"email","password"}` | `200` access token (JWT, 15 min) + refresh token, or the same `401` for every failure |
| `POST /api/auth/refresh` `{"refreshToken"}` | Rotates the pair (7 days idle, 30 days max). Reusing an old refresh token revokes that sign-in |
| `POST /api/auth/sign-out` `{"refreshToken"}` | `204` always; revokes this sign-in |
| `GET /api/auth/me` (Bearer) | The signed-in account |
| `POST /api/auth/password` (Bearer) | Change password; other devices are signed out and a fresh pair is returned |

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `APP_BASE_URL` | `http://localhost:8080` | Base of the link in the email |
| `APP_MAIL_FROM` | `no-reply@example.com` | Sender address |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | `localhost` / `1025` | SMTP server |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | empty | Only for real SMTP; never commit them |
| `APP_JWT_PRIVATE_KEY` / `APP_JWT_PUBLIC_KEY` | empty (a key pair is generated at startup, dev only) | ES256 signing key pair as PEM. Required with the `prod` profile |
| `APP_JWT_KEY_ID` | `dev` | `kid` header of issued tokens |

Generate a P-256 key pair:

```bash
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out jwt-private.pem   # PKCS#8
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem                         # X.509 SPKI
export APP_JWT_PRIVATE_KEY="$(cat jwt-private.pem)" APP_JWT_PUBLIC_KEY="$(cat jwt-public.pem)"
```

## Known limitations

- Rate-limit counters are kept **in memory**: they apply per instance and reset on restart (research R6).
- Behind a reverse proxy, enable `server.forward-headers-strategy` only if the proxy is trusted; otherwise clients can spoof their IP and bypass the per-IP limit (research R7).
- Sign-in throttling and rate limits are in memory (per instance).
- Without `APP_JWT_*` keys a new key is generated at each start, so access tokens (≤ 15 min) do not survive a restart; refresh tokens do.
