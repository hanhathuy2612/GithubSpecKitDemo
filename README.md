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

## Endpoints

| Method and path | Purpose |
|---|---|
| `POST /api/password-reset/requests` `{"email"}` | Request a link. Always `202` with the same message |
| `POST /api/password-reset/confirm` `{"token","newPassword","confirmPassword"}` | Set a new password. `200`, or `400` with Problem Details |
| `GET/POST /forgot-password` | Page for requesting a link |
| `GET/POST /reset-password?token=…` | Page for setting a new password. Opening the link never consumes it |

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `APP_BASE_URL` | `http://localhost:8080` | Base of the link in the email |
| `APP_MAIL_FROM` | `no-reply@example.com` | Sender address |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | `localhost` / `1025` | SMTP server |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | empty | Only for real SMTP; never commit them |

## Known limitations

- Rate-limit counters are kept **in memory**: they apply per instance and reset on restart (research R6).
- Behind a reverse proxy, enable `server.forward-headers-strategy` only if the proxy is trusted; otherwise clients can spoof their IP and bypass the per-IP limit (research R7).
- There is no sign-in or session management. Accounts are seeded, and the credential check is internal (`CredentialService`).
