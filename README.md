# Reset Password Demo

A Spec Kit demo project: "Forgot password, reset via email", built with Java 21 and Spring Boot 4.

- A user enters their email and receives a reset link.
- The link expires after **15 minutes** and works **once**.
- Unknown, disabled and rate-limited emails get **the same response** as real ones, so nobody can probe which accounts exist.

The project follows the [constitution](.specify/memory/constitution.md): thin controllers, business logic in services, automated tests for every endpoint, and no plain-text tokens or passwords.

## Documents

| | |
|---|---|
| Specification | [specs/001-reset-password/spec.md](specs/001-reset-password/spec.md) |
| Plan and design decisions | [plan.md](specs/001-reset-password/plan.md), [research.md](specs/001-reset-password/research.md) |
| Data model and contracts | [data-model.md](specs/001-reset-password/data-model.md), [contracts/](specs/001-reset-password/contracts/) |
| Run and validation guide | [quickstart.md](specs/001-reset-password/quickstart.md) |
| Tasks | [tasks.md](specs/001-reset-password/tasks.md) |

## Quick start

```bash
./gradlew test                         # unit and endpoint tests (in-memory H2, fake mail sender)
./gradlew test -PincludeTags=timing    # SC-004 timing check (also a separate CI job)

docker compose up -d mailpit           # local SMTP :1025, inbox UI at http://localhost:8025
./gradlew bootRun                      # app at http://localhost:8080/forgot-password
```

Demo accounts: `alice@example.com` and `bob@example.com` (active), `disabled@example.com` (disabled). Their initial password is `demo-password-1`.

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
