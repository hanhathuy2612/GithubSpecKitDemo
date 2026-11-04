---

description: "Task list for 002-user-auth (Sign Up, Sign In, Sign Out, Change Password)"
---

# Tasks: User Authentication – Sign Up, Sign In, Sign Out, Change Password

**Input**: `specs/002-user-auth/spec.md`, `plan.md` (incl. Design Summary), `research.md` (R1–R11)

**Tests**: REQUIRED (spec FR-019, Constitution III). Each story writes its tests first and checks that they FAIL before implementing. Lessons from 001 apply: every api/page test extends `IntegrationTestBase`; test doubles use `@Primary`; email is sent `afterCommit`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: US1–US5 from spec.md

## Path Conventions

The same single project as 001:

- Main code: `src/main/java/com/example/resetpassword/` (abbreviated below as `main/`)
- Tests: `src/test/java/com/example/resetpassword/` (abbreviated below as `test/`)
- Resources: `src/main/resources/`

---

## Phase 1: Setup

- [ ] T001 Add `implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")` to `build.gradle.kts` (artifact verified for Boot 4.0.9 in plan.md)
- [ ] T002 Add JWT settings to `src/main/resources/application.yml`: `app.jwt.issuer: reset-password-demo`, `app.jwt.private-key: ${APP_JWT_PRIVATE_KEY:}`, `app.jwt.key-id: ${APP_JWT_KEY_ID:dev}`. Extend `main/config/AppProperties.java` with a nested `Jwt(String issuer, String privateKey, String keyId)` record

---

## Phase 2: Foundational (blocks all stories)

**⚠️ No story work starts until this phase is complete and the full 001 suite is still green.**

- [ ] T003 Create `src/main/resources/db/migration/V3__auth.sql`:
  - alter `user_account`: replace check `ck_user_account_status` with `status IN ('ACTIVE','DISABLED','UNVERIFIED')`; add `session_version INT NOT NULL DEFAULT 0`;
  - table `email_verification_token`: `id` BIGINT identity PK, `account_id` FK → `user_account.id` (indexed), `token_hash CHAR(64) NOT NULL UNIQUE`, `issued_at`/`expires_at` TIMESTAMP WITH TIME ZONE NOT NULL, `used_at`/`invalidated_at` nullable;
  - table `auth_session`: `id VARCHAR(36)` PK (random UUID), `account_id` FK (indexed), `created_at`/`absolute_expires_at` NOT NULL, `revoked_at` nullable, `revoke_reason VARCHAR(32)` nullable;
  - table `refresh_token`: `id` BIGINT identity PK, `session_id` FK → `auth_session.id` (indexed), `token_hash CHAR(64) NOT NULL UNIQUE`, `issued_at`/`expires_at` NOT NULL, `rotated_at` nullable.
- [ ] T004 [P] Add `UNVERIFIED` to `main/domain/AccountStatus.java`. Add `sessionVersion` (int) to `main/domain/UserAccount.java`. Add to `main/repository/UserAccountRepository.java`:
  - `@Modifying` `bumpSessionVersion(id)`, which runs `session_version = session_version + 1`;
  - `@Modifying` `activateIfUnverified(id, now)`, which returns the row count;
  - `@Modifying` `updatePendingPassword(id, hash, now)`, which only applies when the status is UNVERIFIED;
  - a public factory `UserAccount.unverified(email, hash, now)` for new registrations.
- [ ] T005 [P] Create the entities and repositories in `main/domain/` and `main/repository/`, per plan.md › Design Summary › Data:
  - `EmailVerificationToken` with `isUsable(now)` (same rule as `PasswordResetToken`) and `EmailVerificationTokenRepository` (`findByTokenHash`, `invalidateUnusedForAccount`, `markUsedIfUsable`);
  - `AuthSession` with `isRevoked()` and `AuthSessionRepository`, with `@Modifying` `revoke(id, now, reason)` and `revokeAllForAccount(accountId, now, reason)` (`WHERE revoked_at IS NULL`) and `revokeAllForAccountExcept(accountId, keepSessionId, now, reason)`;
  - `RefreshToken` and `RefreshTokenRepository` with `findByTokenHash` and `@Modifying` `markRotatedIfCurrent(id, now)`, which runs `SET rotated_at = :now WHERE id = :id AND rotated_at IS NULL`.
- [ ] T006 [P] Add `boolean isLimited(String key, int limit, Duration window)`, which counts without recording, and `void record(String key)` to `main/ratelimit/RateLimiter.java`, and implement both in `InMemorySlidingWindowRateLimiter.java`. Extend `test/unit/RateLimiterTest.java`: `isLimited` never records, `record` + `isLimited` reach the limit exactly, and windows expire with `MutableClock`
- [ ] T007 [P] Create `main/security/JwtKeyConfig.java`:
  - Parse `app.jwt.private-key` (PKCS#8 PEM, EC P-256) into an `ECKey` with `kid = app.jwt.key-id`.
  - If it is blank and the `prod` profile is not active, generate a P-256 key and log `auth.jwt_key_generated` at WARN. If it is blank with `prod`, throw at startup (research R2).
  - Expose a `JwtEncoder` (`NimbusJwtEncoder`) and a `JwtDecoder` (`NimbusJwtDecoder.withPublicKey(...).signatureAlgorithm(ES256)`).
- [ ] T008 Create `main/security/AccessTokenIssuer.java` with `issue(long accountId, String sessionId, int sessionVersion)`. It returns a token plus `expiresIn = 900`, using the claims `iss`, `sub` (account id as a string), `sid`, `sv`, `iat` and `exp = iat + 15 min` from the injected `Clock`, plus a random `jti`, and header `alg = ES256` with `kid`. No email or other PII goes into the claims (plan.md › JWT)
- [ ] T009 Create `main/security/SessionVersionValidator.java` (`OAuth2TokenValidator<Jwt>`). It loads the account by `sub` and fails unless the status is `ACTIVE` and `sv == session_version` (research R5). Combine it with the issuer validator and a `JwtTimestampValidator` that uses the injected `Clock` and zero clock skew, so tests can drive expiry
- [ ] T010 Create `main/config/SecurityConfig.java` with a single `SecurityFilterChain` (research R1):
  - stateless, CSRF disabled, form login and HTTP basic disabled;
  - `permitAll` for `/forgot-password`, `/reset-password`, `/api/password-reset/**`, `/api/auth/register`, `/api/auth/sign-in`, `/api/auth/refresh`, `/api/auth/sign-out` and `/verify-email`;
  - `authenticated` for `/api/auth/me` and `/api/auth/password`, and `denyAll` for anything else;
  - `oauth2ResourceServer(jwt)` with the T007 decoder and the T009 validators;
  - an entry point that returns `401` with `WWW-Authenticate: Bearer` and Problem `type /problems/unauthenticated` (title "Authentication required."), identical for every cause.
- [ ] T011 Create `main/service/auth/AuthSessionService.java` (shared core):
  - `IssuedCredentials startSession(UserAccount account)`: creates an `auth_session` (`absolute_expires_at = now + 30 d`) and a refresh token (`expires_at = min(now + 7 d, absolute_expires_at)`), stores only `TokenGenerator.hash(raw)`, and returns the raw refresh token plus the T008 access token.
  - `revokeAllForAccount(accountId, reason)`: revokes every active session and bumps `session_version` in the caller's transaction. Logs `auth.sessions_revoked reason= count= accountId=`.
  - Put the record `IssuedCredentials(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt)` in `service/auth/`.
- [ ] T012 Extend `test/support/IntegrationTestBase.java`. Its `@BeforeEach`:
  1. Delete from `refresh_token`, `auth_session` and `email_verification_token`, and delete the non-seeded `user_account` rows. These deletes run before the existing resets.
  2. Reset `session_version = 0` for the seeded accounts.
  3. Reset every `SignInThrottle` bean via `ObjectProvider` (a no-op until US1).

  Add these helpers:
  - `signIn(email, password)`, returning parsed `IssuedCredentials` from `POST /api/auth/sign-in`;
  - `bearer(token)`, a request post-processor;
  - `jwtWith(claims...)`, which mints a token with the context `JwtEncoder` for tampering tests.
- [ ] T013 Regression checkpoint: run `./gradlew test`. All 80 tests from 001 must still pass with Spring Security on the classpath, in particular `ResetPasswordPageTest`'s `Cache-Control: no-store` and the 001 API responses. If a default security header overrides a 001 header, configure `headers()` in `SecurityConfig` rather than changing the 001 tests

**Checkpoint**: The app boots, V3 applies, and the 001 suite is green.

---

## Phase 3: User Story 1 - Sign in and stay signed in (P1) 🎯 MVP

**Goal**: Sign in with email and password. Refresh with rotation and reuse revocation, and use "who am I". The seeded accounts work immediately.

**Independent Test**: Sign in as alice, call `/me`, advance the clock 15 min (`/me` returns 401), refresh, and call `/me` again (200).

### Tests (write first, must FAIL)

- [ ] T014 [P] [US1] Unit tests for `SignInThrottle` in `test/unit/SignInThrottleTest.java` (`MutableClock`):
  - after failure N, the next attempt for that email is blocked for `min(2^(N-1), 30)` seconds, so 1, 2, 4, 8, 16, 30, 30 s;
  - a success resets the email's counter;
  - unknown emails are tracked the same way;
  - the 21st failure from one IP within an hour blocks that IP;
  - IP successes are not counted.
- [ ] T015 [P] [US1] Unit tests for `AuthSessionService` in `test/unit/AuthSessionServiceTest.java` (Mockito):
  - `startSession` stores only hashes, and refresh expires at the earlier of +7 d and +30 d;
  - a refresh rotates the token: the old one is marked rotated and the new one is issued;
  - a rotated token presented again revokes the session (`REUSE`) and returns invalid;
  - `markRotatedIfCurrent` returning 0 is treated as reuse;
  - a token idle for 7 d → invalid;
  - refreshing on day 29 gives a token that expires at the 30-day cap; at the cap → invalid;
  - a revoked session → invalid;
  - a disabled account → invalid.
- [ ] T016 [P] [US1] Unit tests for `SignInService` in `test/unit/SignInServiceTest.java`:
  - success resets the throttle and starts a session;
  - a wrong password, an unknown email, a DISABLED account and an UNVERIFIED account (wrong password) all return the same `FAILED` outcome;
  - an unknown email still calls `passwordEncoder.matches` exactly once, against the dummy hash (research R6);
  - a throttled attempt returns `FAILED` without calling `matches`.
- [ ] T017 [P] [US1] API tests in `test/api/SignInApiTest.java`:
  - success → `200` with `tokenType` `Bearer`, `expiresIn` 900, a 43-char `refreshToken` and a JWT `accessToken` whose header has `alg` ES256 and `kid`, and whose claims are `sub`/`sid`/`sv`/`iss` with no email;
  - every failure cause → a byte-identical `401 /problems/invalid-credentials` ("Email or password is incorrect.");
  - a malformed body → `400 /problems/validation`;
  - after 21 failed sign-ins from one IP, even the correct password → the identical 401.
- [ ] T018 [P] [US1] API tests in `test/api/RefreshApiTest.java`:
  - rotation: the old token → 401 and the new one → 200;
  - reuse of a rotated token → 401, and the session's newest token is also revoked;
  - two concurrent refreshes with the same token (`CountDownLatch`, two threads) → exactly one 200, after which the session is revoked;
  - 7 days idle → 401;
  - the 30-day cap → 401;
  - every failure → an identical `401 /problems/invalid-refresh-token`.
- [ ] T019 [P] [US1] API tests in `test/api/MeApiTest.java`:
  - a valid token → `200 {id, email}`;
  - all of these → an identical `401` with `WWW-Authenticate: Bearer`: no header; expired (the clock advanced 15 min); a tampered signature; a wrong issuer; `alg` none or HS256; a stale `sv` (bump `session_version` via JDBC); a disabled account.

### Implementation

- [ ] T020 [P] [US1] Create `main/ratelimit/SignInThrottle.java` (research R7):
  - per-email state is a consecutive-failure count and a `nextAllowedAt` instant, held in a `ConcurrentHashMap`;
  - the per-IP failure cap uses `RateLimiter.isLimited` / `record` with key `signin-ip:<ip>`, 20 per hour;
  - methods: `boolean allowed(email, ip)`, `void failure(email, ip)`, `void success(email)` and `reset()`.
- [ ] T021 [US1] Add `Optional<IssuedCredentials> refresh(String rawRefreshToken)` to `main/service/auth/AuthSessionService.java`, implementing research R4 in one `TransactionTemplate`. A malformed token (`TokenGenerator.isWellFormed`) → empty with no lookup. Logs `auth.refresh outcome=`
- [ ] T022 [US1] Create `main/service/auth/SignInService.java` with `SignInOutcome signIn(rawEmail, password, clientIp)`, a sealed `Success(IssuedCredentials)` or `FAILED`:
  1. Normalize the email.
  2. If the throttle does not allow the attempt → `FAILED`.
  3. Look up the account and run exactly one `matches`: against the real hash, or against a dummy BCrypt(12) hash computed at construction for an unknown email. Passwords over 72 bytes count as a mismatch without calling `matches`.
  4. Succeed only if `ACTIVE` and the password matches, then `startSession`.
  5. Record the result with the throttle.

  Logs `auth.sign_in outcome= reason=` with the account id only.
- [ ] T023 [P] [US1] Create the DTOs in `main/web/api/dto/`: `SignInRequest(@NotBlank @Email @Size(max=254) email, @NotNull password)`, with the email stripped in a compact constructor and `toString` redacted; `RefreshRequest(@NotBlank refreshToken)`, redacted; `TokenResponse(accessToken, tokenType, expiresIn, refreshToken, refreshExpiresAt)`; and `MeResponse(id, email)`
- [ ] T024 [US1] Create `main/web/api/AuthApiController.java` (`/api/auth`):
  - `POST /sign-in` → 200 `TokenResponse`, or 401 Problem `/problems/invalid-credentials`;
  - `POST /refresh` → 200, or 401 `/problems/invalid-refresh-token`;
  - `GET /me` (`@AuthenticationPrincipal Jwt`) → `MeResponse` via `AccountQueryService.findActive(sub)`. Create that one-method read service in `service/auth/`, so the controller makes no repository call (Constitution I).

  Add the new problem types to `ApiExceptionHandler`'s package scope (validation stays shared).

**Checkpoint**: T014–T019 pass, and the 001 suite is still green.

---

## Phase 4: User Story 2 - Sign up with email verification (P1)

**Goal**: Enumeration-safe registration, a 24-hour single-use verification link, a minimal verification page, and FR-003a resend on unverified sign-in.

**Independent Test**: Register `carol@example.com`, extract the link from `FakeEmailSender`, open `/verify-email?token=…`, and sign in (200).

### Tests (write first, must FAIL)

- [ ] T025 [P] [US2] Unit tests for `RegistrationService` in `test/unit/RegistrationServiceTest.java`:
  - **new email**: an UNVERIFIED account and a hashed 24-hour link are created, and a verification email is sent;
  - **email already UNVERIFIED**: the pending hash is replaced, old links are invalidated, and a new link is sent;
  - **ACTIVE email**: no account change, and an "attempted registration" email is sent;
  - **DISABLED email**: nothing is sent;
  - every branch returns the same outcome and calls `passwordEncoder.encode` exactly once;
  - password rule failures (001 `PasswordPolicy`) → violations, and no email is sent;
  - `verifyEmail`: under 24 h → activated; exactly at 24 h, already used, invalidated, unknown or malformed → `INVALID`.
- [ ] T026 [P] [US2] API tests in `test/api/RegisterApiTest.java`:
  - new, unverified, active and disabled emails → a byte-identical `202 {"message":"Check your email to finish creating your account."}`;
  - the email captured per case is: a verification link for new and unverified, a notice for active (subject "Someone tried to create an account with your email", with no verification link), and nothing for disabled;
  - an invalid email or a password rule failure → `400 /problems/validation` with 001 codes, and no email;
  - the 4th registration for one email within 15 min → the identical 202 with no email;
  - an UNVERIFIED account signing in with the correct password → the identical 401, plus a new verification email (FR-003a); with a wrong password → no email.
- [ ] T027 [P] [US2] Page tests in `test/page/VerifyEmailPageTest.java`:
  - a valid link → "Your email is verified. You can now sign in.", the account is ACTIVE, and sign-in → 200;
  - reused, expired (the clock advanced 24 h), invalidated, unknown, malformed and missing tokens → "This link is invalid or has expired.";
  - every response has `Referrer-Policy: no-referrer`.

### Implementation

- [ ] T028 [P] [US2] Add `verification(rawToken)`, which builds a link to `${app.base-url}/verify-email?token=…` with the text "expires in 24 hours", and `attemptedRegistration()`, which offers only sign-in and `/forgot-password` with no token, to a new `main/mail/AuthEmailComposer.java`. Add `@Async("mailExecutor")` `sendVerification(accountId, email, rawToken)` and `sendAttemptedRegistration(accountId, email)` to `main/mail/ResetMailDispatcher.java`, using the same failure logging
- [ ] T029 [US2] Create `main/service/auth/RegistrationService.java`, implementing research R6 branches and FR-011/FR-012:
  - `register(rawEmail, password, confirm, clientIp)`:
    1. Always run `encode` first.
    2. Validate with the 001 `PasswordPolicy` (the current hash is null).
    3. Apply the 001 `RateLimiter` keys `register-email:` and `register-ip:` (3 per 15 min, 20 per hour).
    4. Branch on the account's state.
    5. Send email via `AfterCommit`.
  - `verifyEmail(rawToken)`, returning `VERIFIED` or `INVALID`: one transaction with a conditional `markUsedIfUsable` plus `activateIfUnverified`.
  - `resendVerification(UserAccount)`, used by FR-003a and subject to the same email limit.
- [ ] T030 [US2] Make `AfterCommit` public in `main/service/AfterCommit.java`, so `service/auth` can use it. Then call `RegistrationService.resendVerification` from `SignInService` when the account is UNVERIFIED and the password matches; the outcome stays `FAILED` (FR-003a)
- [ ] T031 [P] [US2] Add DTO `RegisterRequest(email, password, confirmPassword)` (email stripped, redacted) and `POST /api/auth/register` → 202 to `AuthApiController`, mapping violations exactly like 001 `/confirm`
- [ ] T032 [P] [US2] Create `main/web/page/VerifyEmailPageController.java` (`GET /verify-email`, an optional `token` param, the no-referrer and no-store headers) and `src/main/resources/templates/verify-email-result.html`, which shows the message and, on success, a "Sign in" hint

**Checkpoint**: T025–T027 pass, and US1 still passes.

---

## Phase 5: User Story 3 - Sign out (P2)

**Goal**: Revoke the current sign-in's refresh token. The call is idempotent.

**Independent Test**: Sign in, sign out, then refresh with the old refresh token → 401.

- [ ] T033 [P] [US3] API tests in `test/api/SignOutApiTest.java`:
  - sign out → `204`, after which refreshing with that token → 401;
  - another session of the same account still refreshes;
  - an unknown, already revoked, rotated or malformed token → `204` with nothing changed;
  - the access token keeps working until it expires (the clock at +14 min → `/me` 200; at +15 min → 401).
- [ ] T034 [US3] Add `signOut(rawRefreshToken)` to `AuthSessionService`: revoke the session with `SIGN_OUT` if the token resolves to a non-revoked session, and otherwise do nothing; always return. Add `POST /api/auth/sign-out` → 204 to `AuthApiController`

---

## Phase 6: User Story 4 - Change password while signed in (P2)

**Goal**: Require the current password; revoke other sessions; bump the session version; return a fresh pair; send the notification email.

**Independent Test**: Sign in twice (A and B), change the password with A, then B refresh → 401, B `/me` → 401, the new A token → 200, and sign-in with the new password → 200.

- [ ] T035 [P] [US4] Unit tests in `test/unit/PasswordChangeServiceTest.java`:
  - success updates the hash, bumps the version, revokes the other sessions (`PASSWORD_CHANGED`), keeps the current one, rotates its refresh token, issues an access token with the new `sv`, and sends a notification after commit;
  - a wrong current password → `WRONG_CURRENT_PASSWORD`, and a failure is recorded;
  - after 5 failures in 15 min → `TOO_MANY_ATTEMPTS` without calling `matches`;
  - 001 rule failures → violations and nothing changes.
- [ ] T036 [P] [US4] API tests in `test/api/ChangePasswordApiTest.java`:
  - the full independent-test flow above;
  - the old access token on A → 401 (stale `sv`);
  - a wrong current password → `400 /problems/validation` with code `WRONG_CURRENT_PASSWORD`;
  - the 6th attempt within 15 min → `429 /problems/too-many-attempts`;
  - no token → 401;
  - a "Your password was changed" email is captured with no token in it.
- [ ] T037 [US4] Create `main/service/auth/PasswordChangeService.java` implementing research R9. It uses the `sid`/`sub` claims passed in by the controller and the `RateLimiter` key `pwchange:<accountId>` (5 per 15 min via `isLimited`/`record`). Logs `auth.password_changed`
- [ ] T038 [US4] Add DTO `ChangePasswordRequest` (redacted) and `POST /api/auth/password` (`@AuthenticationPrincipal Jwt`) → 200 `TokenResponse`, 400 or 429 Problem, to `AuthApiController`

---

## Phase 7: User Story 5 - Password reset signs out everywhere (P2)

**Goal**: 001 reset revokes all sessions and bumps the version (FR-017).

**Independent Test**: Sign in, complete a 001 reset for the same account, then refresh → 401 and `/me` → 401.

- [ ] T039 [P] [US5] API test in `test/api/ResetRevokesSessionsTest.java`: two sessions for alice; seed a 001 reset token and `POST /api/password-reset/confirm` → 200; both refresh tokens → 401, both access tokens → 401, and sign-in with the new password → 200
- [ ] T040 [US5] Inject `AuthSessionService` into `main/service/PasswordResetService.java`, and call `revokeAllForAccount(accountId, PASSWORD_RESET)` inside the existing `resetPassword` transaction, right after `updatePasswordHash`. Update `test/support/ServiceFixtures.java` with a mocked `AuthSessionService`, and add a verify to `test/unit/PasswordResetServiceResetTest.java`

---

## Phase 8: Polish & Cross-Cutting

- [ ] T041 [P] SC-003 timing test `test/api/AuthEnumerationTimingTest.java`, tagged `@Tag("timing")`. After a warm-up, take the median of 30 samples for each pair and assert a difference of ≤ 50 ms:
  - sign-in with an unknown email vs. a wrong password;
  - registration with a new vs. an existing email.

  Reset `SignInThrottle` and `RateLimiter` before each sample.
- [ ] T042 [P] Secrecy test `test/api/AuthSecrecyTest.java`. Use `OutputCaptureExtension` across register → verify → sign-in → refresh → change password → sign-out, and check:
  - logs contain no access token, refresh token, verification token, password or email, but do contain the `auth.*` event names;
  - every `refresh_token.token_hash` and `email_verification_token.token_hash` matches `^[0-9a-f]{64}$`, and no column contains a captured raw token.
- [ ] T043 [P] Update living docs:
  - `README.md`: the 002 row in the Documents table, the endpoints, `APP_JWT_PRIVATE_KEY`/`APP_JWT_KEY_ID` in the env table (with how to generate a P-256 PKCS#8 key using `openssl`), and the manual check steps for 002;
  - the `specs/001-reset-password/plan.md` Design Summary: note that reset now also revokes sessions (002 FR-017).
- [ ] T044 Run `./gradlew clean test` and `./gradlew test -PincludeTags=timing`. Then run the manual check with `bootRun` and `curl`: register, verify via the captured mail, sign in, `/me`, refresh, change password, the old token → 401, sign-out. Append the results to this file
- [ ] T045 Constitution review: controllers in `web/` import no repository, mail or security internals beyond `Jwt`; every endpoint in plan.md › Interfaces has tests; refresh and verification tokens are hashed only; secrets come from env. Mark `tasks.md` frozen and set the spec Status to `Implemented`

---

## Dependencies & Execution Order

```
Setup (T001–T002) → Foundational (T003–T013) → US1 (T014–T024) ─┬─▶ US2 (T025–T032)
                                                                ├─▶ US3 (T033–T034)
                                                                ├─▶ US4 (T035–T038)
                                                                └─▶ US5 (T039–T040)
                                                                         └─▶ Polish (T041–T045)
```

- US2–US5 all need US1: sign-in to obtain tokens, and `AuthSessionService.refresh` to assert revocation.
- US2–US5 are independent of each other, but they edit the shared `AuthApiController.java` and `AuthSessionService.java`, so a single implementer does them in ID order.
- Within each story: tests → services → controller.

### Parallel opportunities

- Foundational: T004–T007 touch separate files.
- Every test task within a story is marked [P].
- US1: T020 and T023 can run alongside the tests.

## Parallel Example: User Story 1

```bash
Task: "T014 SignInThrottleTest"   Task: "T015 AuthSessionServiceTest"   Task: "T016 SignInServiceTest"
Task: "T017 SignInApiTest"        Task: "T018 RefreshApiTest"           Task: "T019 MeApiTest"
Task: "T020 SignInThrottle"       Task: "T023 auth DTOs"
```

## Implementation Strategy

1. **MVP**: Setup, Foundational and US1. The seeded accounts can then sign in, refresh and call `/me`. Validate this with the independent test.
2. Next, US2: self-service accounts.
3. Then US4 and US5: the security guarantees around password changes.
4. Then US3: sign-out.
5. Polish: timing, secrecy, docs and review.

## Notes

- The 001 suite must stay green after every phase (Spring Security now wraps every route).
- After delivery this file is frozen, as in 001. Later changes start from `spec.md`.
