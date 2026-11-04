# Implementation Plan: Forgot Password – Reset via Email

**Branch**: `001-reset-password` (work tracked on `main`) | **Date**: 2026-11-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-reset-password/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Users request a password reset by email and set a new password through a single-use
link valid for 15 minutes; unknown emails get the same response as known ones. The
feature is a single Spring Boot web application exposing a JSON API and two
server-rendered Thymeleaf pages. Both entry points use thin controllers that delegate to
one `PasswordResetService`. Reset tokens are 256-bit random values. Only their SHA-256
hash is stored, in an H2 database managed by Flyway (which also seeds demo accounts).
Email goes through an `EmailSender` port: SMTP in runtime, a capturing fake in tests,
Mailpit locally. Rate limiting uses an in-memory sliding window keyed by email and by IP.
Time comes from an injected `Clock` so expiry rules are deterministic in tests.

## Technical Context

**Language/Version**: Java 21 (LTS)

**Primary Dependencies**: Spring Boot 4.0.x (latest stable patch at implementation time; 3.5.x is the fallback, see research R1). Starters: web (Spring MVC), thymeleaf, validation, data-jpa, mail. Also Flyway, `spring-security-crypto` (password hashing only, no Spring Security filter chain).

**Storage**: H2. In-memory for tests; file-based (`./data/`) for local runs. Schema and seed data come from Flyway migrations.

**Testing**: JUnit 5, AssertJ, Spring Boot Test with MockMvc (endpoint tests), plain unit tests with fakes for the service layer. Gradle `test` task.

**Target Platform**: Linux/macOS/Windows JVM server; local run via `./gradlew bootRun`.

**Project Type**: Web service with server-rendered pages (single project).

**Performance Goals**: The reset-request endpoint's median latency for known and unknown emails differs by ≤ 50 ms (SC-004). The email is handed to SMTP within 5 s at p95 (SC-005).

**Constraints**: No plain-text tokens stored or logged. No account enumeration via message, status, structure or timing. Single application instance (the in-memory rate limiter is per instance; see R6).

**Scale/Scope**: Demo scale. A handful of seeded accounts, 4 HTTP endpoints for pages, 2 API endpoints, 2 tables.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-design | Post-design |
|-----------|------|-----------|-------------|
| I. Thin Controllers | Controllers only parse/validate request shape, call the service, map the result to a response | ✅ Planned: `PasswordResetApiController` and `PasswordResetPageController` call only `PasswordResetService` | ✅ Contracts map each endpoint to one service method; no business branching in controllers (see contracts/) |
| II. Business Logic in Services | Token issue, expiry, single use, invalidation, rate limit and password rules live in services; dependencies injectable | ✅ `PasswordResetService` owns the rules. `Clock`, `EmailSender`, `RateLimiter`, `TokenGenerator`, repositories and `PasswordEncoder` are injected | ✅ data-model.md state transitions are enforced only by the service and its repository queries |
| III. Automated Tests for Every Endpoint | Every endpoint has success, validation and edge-case tests; service rules are unit-tested | ✅ Required by spec FR-017; test layout below | ✅ quickstart.md lists the scenario → test mapping; each contract lists its required test cases |
| IV. No Plain-Text Reset Tokens | Only a hash is stored; tokens and passwords are never logged | ✅ SHA-256 of a 256-bit token is stored; BCrypt for passwords | ✅ `token_hash` column only; logging rules in research R9; `Referrer-Policy: no-referrer` on the reset page |
| Security Constraints | No enumeration; CSPRNG tokens with expiry; secrets from env | ✅ | ✅ Identical 202 response, async email (R5), `SecureRandom`, SMTP settings via env |

No violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-reset-password/
├── spec.md              # What and why: source of truth for behavior (living)
├── plan.md              # This file: stack, constitution check, design summary (living)
├── research.md          # Technical decisions and rationale R1–R13 (living)
├── tasks.md             # Implementation log, frozen after delivery
└── checklists/          # Spec quality checklist, frozen
```

Field-level details (tables, JSON shapes, page templates, email text) live in the code and are
verified by tests. They are not duplicated here (see Design Summary).

### Source Code (repository root)

```text
build.gradle.kts
settings.gradle.kts
gradlew, gradlew.bat, gradle/wrapper/
compose.yaml                         # Mailpit for local runs

src/main/java/com/example/resetpassword/
├── ResetPasswordApplication.java
├── config/                          # Clock bean, PasswordEncoder bean, async executor, app properties
├── web/
│   ├── api/                         # PasswordResetApiController + request/response DTOs + error mapping
│   └── page/                        # PasswordResetPageController (Thymeleaf forms)
├── service/                         # PasswordResetService, PasswordPolicy, ResetOutcome types
├── domain/                          # UserAccount, PasswordResetToken (JPA entities)
├── repository/                      # UserAccountRepository, PasswordResetTokenRepository
├── security/                        # TokenGenerator (SecureRandom + SHA-256)
├── ratelimit/                       # RateLimiter interface + InMemorySlidingWindowRateLimiter
└── mail/                            # EmailSender port, SmtpEmailSender, ResetEmailComposer

src/main/resources/
├── application.yml
├── db/migration/                    # V1__schema.sql, V2__seed_accounts.sql
└── templates/                       # forgot-password.html, reset-password.html, reset-invalid.html, reset-success.html

src/test/java/com/example/resetpassword/
├── unit/                            # PasswordResetService, PasswordPolicy, TokenGenerator, RateLimiter
├── api/                             # MockMvc tests per API endpoint (contract + edge cases)
├── page/                            # MockMvc tests per page endpoint
└── support/                         # FakeEmailSender, MutableClock, test fixtures
```

**Structure Decision**: A single Gradle project. The split between `web` and `service`
makes Principles I–II visible in the package layout. A separate frontend is out of scope
per the spec's clarification, so the frontend/backend split isn't used.

## Design Summary

Short reference for reviewers. The linked code and tests are authoritative.

### Data model

| Table | Key rules | Source |
|-------|-----------|--------|
| `user_account` | `email` unique and stored normalized (trimmed, lower-case); `password_hash` BCrypt(12); `status` `ACTIVE`/`DISABLED`; seeded only (alice, bob active; disabled@ disabled; password `demo-password-1`) | `db/migration/V1__schema.sql`, `V2__seed_accounts.sql` |
| `password_reset_token` | `token_hash` = SHA-256 hex (64 chars, unique), never the raw token; `expires_at = issued_at + 15 min`; `used_at` and `invalidated_at` are nullable | `V1__schema.sql`, `domain/PasswordResetToken.java` |

**Token lifecycle:** a token is usable only while `used_at IS NULL AND invalidated_at IS NULL AND now < expires_at` (strict, so it is expired at exactly 15:00). Issuing a new token invalidates the account's unused tokens. A successful reset sets `used_at` through a conditional UPDATE in the same transaction as the password change (R8). Used, invalidated, expired, unknown and malformed tokens are all reported identically.

**Rate limits (in memory, R6):** 3 per normalized email per rolling 15 minutes, and 20 per client IP per rolling hour. Checked before the account lookup.

### Interfaces

| Endpoint | Behavior | Tests |
|----------|----------|-------|
| `POST /api/password-reset/requests` `{email}` | Always `202` with one fixed message for known, unknown, disabled and throttled emails. `400 /problems/validation` for a bad email format | `RequestResetApiTest`, `EnumerationApiTest`, `EnumerationTimingTest` |
| `POST /api/password-reset/confirm` `{token,newPassword,confirmPassword}` | `200` on success. `400 /problems/validation` with `code` `TOO_SHORT`/`TOO_LONG`/`SAME_AS_CURRENT`/`MISMATCH` (token not consumed). `400 /problems/invalid-reset-token` for every token failure. Token checks run before password rules | `ConfirmResetApiTest`, `InvalidTokenApiTest`, `ConcurrentConfirmApiTest` |
| `GET/POST /forgot-password` | Same service call as the API. The page never echoes the email, so it is identical for every account | `ForgotPasswordPageTest` |
| `GET/POST /reset-password?token=` | GET only checks the token (never consumes it). Sends `Referrer-Policy: no-referrer` and `Cache-Control: no-store`. Invalid links render `reset-invalid` | `ResetPasswordPageTest`, `InvalidLinkPageTest` |
| Email: "Reset your password" / "Your password was changed" | Sent via the `EmailSender` port after commit, asynchronously. Failures are logged by account id only | `mail/ResetEmailComposer.java`, `EmailFailureApiTest`, `EmailHandoffLatencyTest` |

Secrecy guarantees (FR-010, FR-015, SC-006) are verified by `LogSecrecyTest` and `TokenStorageTest`. Run instructions are in the [README](../../README.md).

## Complexity Tracking

No constitution violations. Nothing to justify.
