# Implementation Plan: User Authentication – Sign Up, Sign In, Sign Out, Change Password

**Branch**: `002-user-auth` (work tracked on `main`) | **Date**: 2026-11-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-user-auth/spec.md`

**Note**: This plan uses the project's lean format: plan + research only. Field-level details live in code and tests (see the 001 slimming decision).

## Summary

The same Spring Boot application as 001 gains an `/api/auth` API with these endpoints:
register (email only), sign in, refresh, sign out, "who am I" and change password. A server-rendered registration page lets the user choose a password from the emailed link.

**Terminology**: the spec's neutral "access credential" and "refresh credential" are called "access token" and "refresh token" in this plan and in the code.

- **Access credentials** are short-lived JWTs (15 minutes, ES256). Spring Security's resource-server support validates them, plus a custom check of the account's **session version**, so a password change or reset invalidates every access token at once.
- **Refresh credentials** are opaque 256-bit random tokens. Only their SHA-256 hash is stored. Each refresh rotates the token inside a server-side **auth session**: 7 days idle, 30 days absolute. Reusing an already-rotated token revokes the whole session.
- **Registration** takes the email only and never reveals whether it exists. An email with no account gets a registration link (24 hours, hashed, single use); the account is created ACTIVE only when the user chooses a password on the page opened from that link. This prevents pre-registration account takeover (`/speckit-analyze` H1). Existing active owners get an "attempted registration" notice.
- **Sign-in failures** all return one response with equal timing (a dummy BCrypt compare is done for unknown emails). Failures are throttled by per-email exponential backoff and a per-IP cap.
- **001 integration**: a completed password reset now revokes all sessions and bumps the session version.

## Technical Context

**Language/Version**: Java 21 (same as 001)

**Primary Dependencies**: Spring Boot 4.0.9 (existing). New: `spring-boot-starter-security-oauth2-resource-server` (Spring Security + JWT validation via Nimbus JOSE). The artifact name was verified on Maven Central for 4.0.9; the older `spring-boot-starter-oauth2-resource-server` also resolves but is the legacy name.

**Storage**: H2 via Flyway (existing). New migration `V3`: `user_account.session_version`; new tables `registration_token`, `auth_session`, `refresh_token`.

**Testing**: JUnit 5, MockMvc, Mockito (existing). `IntegrationTestBase` is extended to reset the new tables, throttles and clock. JWT keys are generated per test context.

**Target Platform**: JVM server (same as 001)

**Project Type**: Web service (single project, same app as 001)

**Performance Goals**: SC-003. Sign-in (unknown email vs. wrong password) and registration (new vs. existing email) differ by ≤ 50 ms in median latency. Registration involves no password hashing at all, which makes this easy. Protected requests add one primary-key read (session version).

**Constraints**: No account enumeration; no raw tokens or passwords stored or logged; signing key from environment; single instance (in-memory throttles, as in 001 R6).

**Scale/Scope**: Demo scale. 6 API endpoints, 1 page (GET+POST), 3 new tables.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-design | Post-design |
|-----------|------|-----------|-------------|
| I. Thin Controllers | Controllers only parse requests, call one service method and map the result | ✅ `AuthApiController` and `CompleteRegistrationPageController` delegate to the services below. The authenticated account is resolved by Spring Security, not by controller code | ✅ Every endpoint in Design Summary › Interfaces maps to exactly one service call |
| II. Business Logic in Services | Registration, verification, sign-in, throttling, rotation, revocation and password change live in services | ✅ `RegistrationService`, `SignInService`, `AuthSessionService` and `PasswordChangeService`. `Clock`, `EmailSender`, `PasswordEncoder`, the JWT encoder and the throttles are injected | ✅ The JWT session-version check is a small validator that calls `AuthSessionService` |
| III. Tests for Every Endpoint | Success, validation and edge cases per endpoint, plus unit tests for rules | ✅ FR-019, with the test map in Design Summary | ✅ Interface table lists the test class per endpoint |
| IV. No Plain-Text Tokens | Refresh and registration tokens hashed; passwords BCrypt; nothing secret logged | ✅ SHA-256 for refresh and registration tokens; JWTs are not stored at all | ✅ Secrecy tests extended to the new tables and log events (R10) |
| Security Constraints | No enumeration; CSPRNG tokens with expiry; secrets from env | ✅ | ✅ `APP_JWT_PRIVATE_KEY` from env; tests and dev use a generated key (R2) |

**Change to 001 (required by 002 FR-017)**: `PasswordResetService.resetPassword` will call `AuthSessionService.revokeAllForAccount` inside its transaction. Per spec-driven practice, the 001 spec is amended first: FR-013 gets a note that session sign-out is restored by 002 FR-017.

No violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/002-user-auth/
├── spec.md              # What and why (living)
├── plan.md              # This file, including Design Summary (living)
├── research.md          # Decisions R1–R11 (living)
├── tasks.md             # /speckit-tasks output; frozen after delivery
└── checklists/          # Spec quality checklist
```

### Source Code (new and changed, repository root)

```text
src/main/java/com/example/resetpassword/
├── config/SecurityConfig.java            # filter chain: permitAll for 001 + public auth endpoints; JWT for /me, /password
├── security/
│   ├── JwtKeyConfig.java                 # ES256 key from env or generated (R2)
│   ├── AccessTokenIssuer.java            # builds JWT (sub, sid, sv, iat, exp, jti)
│   └── SessionVersionValidator.java      # rejects tokens whose sv != account.session_version
├── domain/   RegistrationToken.java, AuthSession.java, RefreshToken.java
├── repository/ RegistrationTokenRepository, AuthSessionRepository, RefreshTokenRepository
├── ratelimit/  SignInThrottle.java       # per-email backoff + per-IP failure cap (R7)
├── service/auth/
│   ├── RegistrationService.java          # register (email) + checkLink + completeRegistration (password)
│   ├── SignInService.java                # sign in
│   ├── AuthSessionService.java           # create, refresh/rotate, reuse detection, sign out, revokeAll, bumpVersion
│   └── PasswordChangeService.java
├── mail/ AuthEmailComposer.java          # registration-link and attempted-registration emails
└── web/
    ├── api/AuthApiController.java (+ dto/)
    └── page/CompleteRegistrationPageController.java   # + templates/complete-registration.html, registration-complete.html, registration-invalid.html

src/main/resources/db/migration/V3__auth.sql

src/test/java/com/example/resetpassword/
├── unit/  SignInThrottleTest, AuthSessionServiceTest, RegistrationServiceTest, SignInServiceTest, PasswordChangeServiceTest
├── api/   RegisterApiTest, SignInApiTest, RefreshApiTest, SignOutApiTest, MeApiTest, ChangePasswordApiTest,
│          AuthEnumerationTimingTest (@Tag timing), AuthSecrecyTest, ResetRevokesSessionsTest
└── page/  CompleteRegistrationPageTest
```

**Structure Decision**: Extend the existing single project; `service/auth` and `web/api/AuthApiController` keep the 002 code separate from 001 while sharing accounts, the password policy, the token generator and mail.

## Design Summary

Short reference for reviewers. Once implemented, the code and tests are authoritative.

### Data (migration `V3__auth.sql`)

| Table / change | Key rules |
|----------------|-----------|
| `user_account` | New `session_version INT NOT NULL DEFAULT 0` (FR-015a). Statuses unchanged (`ACTIVE`, `DISABLED`); rows are created only by completed registration or seed |
| `registration_token` | `email` (normalized, indexed, **no** account FK), `token_hash` SHA-256 unique, `expires_at = issued + 24 h`, `used_at`, `invalidated_at`. A newer registration for the same email invalidates older links. Completion = conditional `markUsedIfUsable` + insert account in one transaction; the unique email constraint makes a racing second completion fail → invalid |
| `auth_session` | `id` (random UUID), `account_id`, `created_at` (sign-in time), `absolute_expires_at = created_at + 30 d`, `revoked_at`, `revoke_reason` (`SIGN_OUT`, `REUSE`, `PASSWORD_CHANGED`, `PASSWORD_RESET`, `DISABLED`) |
| `refresh_token` | `session_id`, `token_hash` SHA-256 unique, `issued_at`, `expires_at = min(issued + 7 d, session.absolute_expires_at)`, `rotated_at` |

**Refresh token lifecycle** (R4): a refresh token is valid when its session is not revoked, `rotated_at IS NULL` and `now < expires_at`. On refresh, `UPDATE refresh_token SET rotated_at = now WHERE id = ? AND rotated_at IS NULL` must affect exactly one row; then a new token is inserted in the same session. A token that was already rotated, including the loser of a concurrent double refresh, revokes the session with `REUSE`.

### Interfaces

| Endpoint | Behavior | Errors | Tests |
|----------|----------|--------|-------|
| `POST /api/auth/register` `{email}` | Always `202` with "Check your email to finish creating your account." for new, pending, active and disabled emails | `400 /problems/validation` (email format), checked before any lookup | `RegisterApiTest`, timing |
| `GET /complete-registration?token=` | Checks the link only (never consumes it) and renders the choose-password form, or `registration-invalid` | Invalid, expired, used, superseded or malformed → same invalid page | `CompleteRegistrationPageTest` |
| `POST /complete-registration` `token,password,confirmPassword` | Creates the ACTIVE account and consumes the link atomically → `registration-complete` ("Your account is ready. You can now sign in.") | 001 password rule errors re-render the form (link kept); invalid link → `registration-invalid`. `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on both | `CompleteRegistrationPageTest` |
| `POST /api/auth/sign-in` `{email,password}` | `200 {accessToken, tokenType:"Bearer", expiresIn:900, refreshToken, refreshExpiresAt}` | Every failure → `401 /problems/invalid-credentials`, "Email or password is incorrect." | `SignInApiTest`, timing |
| `POST /api/auth/refresh` `{refreshToken}` | `200`, same body shape, rotated pair | `401 /problems/invalid-refresh-token` (one response for all causes) | `RefreshApiTest` |
| `POST /api/auth/sign-out` `{refreshToken}` | `204` always (FR-013) | none | `SignOutApiTest` |
| `GET /api/auth/me` (Bearer) | `200 {id, email}` | `401` + `WWW-Authenticate: Bearer` for missing, expired, tampered or stale-version tokens | `MeApiTest` |
| `POST /api/auth/password` (Bearer) `{currentPassword,newPassword,confirmPassword}` | `200` with a new pair for this session; other sessions revoked; version bumped; notification sent | `400 /problems/validation` (`WRONG_CURRENT_PASSWORD` + 001 codes); `429 /problems/too-many-attempts` after 5 wrong current passwords in 15 min | `ChangePasswordApiTest` |

**JWT** (R1–R3): ES256, header `kid`. Claims: `iss=reset-password-demo`, `sub=<account id>`, `sid=<session id>`, `sv=<session version>`, `iat`, `exp=iat+15 min`, `jti`. No email or other PII.

**Emails**: registration link `${APP_BASE_URL}/complete-registration?token=…` ("expires in 24 hours"); "Someone tried to create an account with your email" (no link to sign up, only to sign in or reset); the existing "Your password was changed".

## Complexity Tracking

No constitution violations. Nothing to justify.
