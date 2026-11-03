# Quickstart & Validation: Forgot Password – Reset via Email

Validation guide that proves the feature works end-to-end. For endpoint details see
[contracts/](./contracts/); for tables and rules see [data-model.md](./data-model.md).

## Prerequisites

- JDK 21
- Docker (only for the local mail catcher, Mailpit)
- Nothing else: the Gradle wrapper and the H2 database are bundled.

## Configuration (environment variables)

| Variable | Local default | Purpose |
|----------|---------------|---------|
| `APP_BASE_URL` | `http://localhost:8080` | Base of the link in the reset email |
| `APP_MAIL_FROM` | `no-reply@example.com` | Sender address |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | `localhost` / `1025` | SMTP server (Mailpit locally) |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | empty | Only for real SMTP; never committed |

## Run automated validation

```bash
./gradlew test                  # all unit + endpoint tests (in-memory H2, FakeEmailSender)
./gradlew test -PincludeTags=timing   # SC-004 timing check, run separately if CI is noisy
```

**Expected**: all tests pass. Every spec acceptance scenario maps to tests:

| Spec scenario | Test location |
|---------------|---------------|
| US1 #1–3 (request link, invalid email, normalization) | `api/` request tests, `page/` forgot-password tests |
| US2 #1–3 (set password, rule errors, old password rejected) | `api/` confirm tests, `page/` reset-password tests, `unit/` PasswordPolicy |
| US3 #1–2 (no enumeration, timing) | `api/` request tests (identical body), timing test |
| US4 #1–3 (expired, used, malformed) | `api/` confirm tests, `page/` reset-password tests, `unit/` service with MutableClock |
| Edge cases (multiple links, concurrency, 15:00 boundary, email failure, rate limit, disabled account) | `unit/` service and rate limiter, `api/` concurrency test |

## Manual end-to-end check

```bash
docker compose up -d mailpit    # SMTP :1025, inbox UI http://localhost:8025
./gradlew bootRun
```

1. Open `http://localhost:8080/forgot-password` and submit `alice@example.com`. **Expect**: the generic confirmation message.
2. Open the Mailpit UI. **Expect**: one "Reset your password" email with a link.
3. Submit `nobody@example.com`. **Expect**: the same message as step 1, and no new email in Mailpit.
4. Click the link and enter a new password (≥ 8 characters) twice. **Expect**: "Your password has been reset", and a "Your password was changed" email in Mailpit.
5. Click the same link again. **Expect**: "This link is invalid or has expired."
6. Request 4 links for `bob@example.com` within 15 minutes. **Expect**: only 3 emails arrive, and the page message is identical every time.
7. Check that no token or password appears in the application logs or in the `token_hash` column (H2 console disabled by default; inspect via test or `./data/` with the H2 shell). Only 64-character hex hashes should be stored.

Seeded demo accounts: `alice@example.com`, `bob@example.com` (ACTIVE) and `disabled@example.com` (DISABLED). Their initial password is `demo-password-1`, stored as a BCrypt hash in `V2__seed_accounts.sql`.

## Known limitations

- Rate-limit counters are in memory: per instance, and reset on restart (research R6).
- Behind a reverse proxy, enable `server.forward-headers-strategy` only for a trusted proxy (research R7).
