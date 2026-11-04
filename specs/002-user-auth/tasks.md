---

description: "Task list for 002-user-auth (Sign Up, Sign In, Sign Out, Change Password)"
---

# Tasks: User Authentication – Sign Up, Sign In, Sign Out, Change Password

**Input**: `specs/002-user-auth/spec.md`, `plan.md` (incl. Design Summary), `research.md` (R1–R11). Revised after `/speckit-analyze` (H1 → password-on-verification; U1–U3, G1, G2 fixed).

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

- [X] T001 Add `implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")` to `build.gradle.kts` (artifact verified for Boot 4.0.9 in plan.md)
- [X] T002 Add JWT settings to `src/main/resources/application.yml`: `app.jwt.issuer: reset-password-demo`, `app.jwt.private-key: ${APP_JWT_PRIVATE_KEY:}`, `app.jwt.key-id: ${APP_JWT_KEY_ID:dev}`. Extend `main/config/AppProperties.java` with a nested `Jwt(String issuer, String privateKey, String keyId)` record

---

## Phase 2: Foundational (blocks all stories)

**⚠️ No story work starts until this phase is complete and the full 001 suite is still green.**

- [X] T003 Create `src/main/resources/db/migration/V3__auth.sql`:
  - alter `user_account`: add `session_version INT NOT NULL DEFAULT 0` (statuses stay `ACTIVE`/`DISABLED`);
  - table `registration_token`: `id` BIGINT identity PK, `email VARCHAR(254) NOT NULL` (normalized, indexed, **no** FK), `token_hash CHAR(64) NOT NULL UNIQUE`, `issued_at`/`expires_at` TIMESTAMP WITH TIME ZONE NOT NULL, `used_at`/`invalidated_at` nullable;
  - table `auth_session`: `id VARCHAR(36)` PK (random UUID), `account_id` FK (indexed), `created_at`/`absolute_expires_at` NOT NULL, `revoked_at` nullable, `revoke_reason VARCHAR(32)` nullable;
  - table `refresh_token`: `id` BIGINT identity PK, `session_id` FK → `auth_session.id` (indexed), `token_hash CHAR(64) NOT NULL UNIQUE`, `issued_at`/`expires_at` NOT NULL, `rotated_at` nullable.
- [X] T004 [P] Add `sessionVersion` (int) to `main/domain/UserAccount.java`, plus a public factory `UserAccount.active(normalizedEmail, passwordHash, now)` for completed registrations. Add `@Modifying` `bumpSessionVersion(id)` (`session_version = session_version + 1`) to `main/repository/UserAccountRepository.java`
- [X] T005 [P] Create the entities and repositories in `main/domain/` and `main/repository/`, per plan.md › Design Summary › Data:
  - `RegistrationToken` (fields per V3) with `isUsable(now)` (same rule as `PasswordResetToken`) and `RegistrationTokenRepository` (`findByTokenHash`, `@Modifying invalidateUnusedForEmail(email, now)`, `@Modifying markUsedIfUsable(id, now)`);
  - `AuthSession` with `isRevoked()` and `AuthSessionRepository`, with `@Modifying` `revoke(id, now, reason)` and `revokeAllForAccount(accountId, now, reason)` (`WHERE revoked_at IS NULL`) and `revokeAllForAccountExcept(accountId, keepSessionId, now, reason)`;
  - `RefreshToken` and `RefreshTokenRepository` with `findByTokenHash` and `@Modifying` `markRotatedIfCurrent(id, now)`, which runs `SET rotated_at = :now WHERE id = :id AND rotated_at IS NULL`.
- [X] T006 [P] Add `boolean isLimited(String key, int limit, Duration window)`, which counts without recording, and `void record(String key)` to `main/ratelimit/RateLimiter.java`, and implement both in `InMemorySlidingWindowRateLimiter.java`. Extend `test/unit/RateLimiterTest.java`: `isLimited` never records, `record` + `isLimited` reach the limit exactly, and windows expire with `MutableClock`
- [X] T007 [P] Create `main/security/JwtKeyConfig.java`:
  - Parse `app.jwt.private-key` (PKCS#8 PEM, EC P-256) and `app.jwt.public-key` (X.509 PEM, added during implementation; see research R2) into an `ECKey` with `kid = app.jwt.key-id`.
  - If it is blank and the `prod` profile is not active, generate a P-256 key and log `auth.jwt_key_generated` at WARN. If it is blank with `prod`, throw at startup (research R2).
  - Expose a `JwtEncoder` (`NimbusJwtEncoder`) and a `JwtDecoder` (`NimbusJwtDecoder.withPublicKey(...).signatureAlgorithm(ES256)`).
- [X] T008 Create `main/security/AccessTokenIssuer.java` with `issue(long accountId, String sessionId, int sessionVersion)`. It returns a token plus `expiresIn = 900`, using the claims `iss`, `sub` (account id as a string), `sid`, `sv`, `iat` and `exp = iat + 15 min` from the injected `Clock`, plus a random `jti`, and header `alg = ES256` with `kid`. No email or other PII goes into the claims (plan.md › JWT)
- [X] T009 Create `main/security/SessionVersionValidator.java` (`OAuth2TokenValidator<Jwt>`). It loads the account by `sub` and fails unless the status is `ACTIVE` and `sv == session_version` (research R5). Set the decoder's validator explicitly to `new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(issuer), timestamp, sessionVersionValidator)`, where `timestamp = new JwtTimestampValidator(Duration.ZERO)` with `setClock(clock)` using the injected `Clock`. **Do not** use `JwtValidators.createDefault…`: its timestamp check uses the system clock and would reject every token minted with the test `MutableClock` (research R1)
- [X] T010 Create `main/config/SecurityConfig.java` with a single `SecurityFilterChain` (research R1):
  - stateless, CSRF disabled, form login and HTTP basic disabled;
  - `permitAll` for `/error` (so error dispatches keep their 404/405/415 status), `/forgot-password`, `/reset-password`, `/api/password-reset/**`, `/api/auth/register`, `/api/auth/sign-in`, `/api/auth/refresh`, `/api/auth/sign-out` and `/complete-registration`;
  - `authenticated` for `/api/auth/me` and `/api/auth/password`, and `denyAll` for anything else;
  - `oauth2ResourceServer(jwt)` with the T007 decoder and the T009 validators;
  - an entry point that returns `401` with `WWW-Authenticate: Bearer` and Problem `type /problems/unauthenticated` (title "Authentication required."), identical for every cause.
- [X] T011 Create `main/service/auth/AuthSessionService.java` (shared core):
  - `IssuedCredentials startSession(UserAccount account)`: creates an `auth_session` (`absolute_expires_at = now + 30 d`) and a refresh token (`expires_at = min(now + 7 d, absolute_expires_at)`), stores only `TokenGenerator.hash(raw)`, and returns the raw refresh token plus the T008 access token.
  - `revokeAllForAccount(accountId, reason)`: revokes every active session and bumps `session_version` in the caller's transaction. Logs `auth.sessions_revoked reason= count= accountId=`.
  - Put the record `IssuedCredentials(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt)` in `service/auth/`.
- [X] T012 Extend `test/support/IntegrationTestBase.java`. Its `@BeforeEach` cleans up in this exact FK-safe order, replacing the existing single `DELETE FROM password_reset_token`:
  1. `DELETE FROM refresh_token`
  2. `DELETE FROM auth_session`
  3. `DELETE FROM registration_token`
  4. `DELETE FROM password_reset_token`
  5. `DELETE FROM user_account WHERE email NOT IN (<3 seeded emails>)`
  6. Reset the seeded accounts' hash and status (existing), plus `session_version = 0`.
  7. Reset every `SignInThrottle` bean via `ObjectProvider` (a no-op until US1).

  Add these helpers:
  - `signIn(email, password)`, returning parsed `IssuedCredentials` from `POST /api/auth/sign-in`;
  - `bearer(token)`, a request post-processor;
  - `jwtWith(claims...)`, which mints a token with the context `JwtEncoder` for tampering tests.
- [X] T013 Regression checkpoint: run `./gradlew test`. All 80 tests from 001 must still pass with Spring Security on the classpath, in particular `ResetPasswordPageTest`'s `Cache-Control: no-store` and the 001 API responses. Add `test/api/SecurityRoutingTest.java`: `PUT /api/password-reset/requests` → `405` (not 401), and an unknown path → `401` (the denyAll rule). If a default security header overrides a 001 header, configure `headers()` in `SecurityConfig` rather than changing the 001 tests

**Checkpoint**: The app boots, V3 applies, and the 001 suite is green.

---

## Phase 3: User Story 1 - Sign in and stay signed in (P1) 🎯 MVP

**Goal**: Sign in with email and password. Refresh with rotation and reuse revocation, and use "who am I". The seeded accounts work immediately.

**Independent Test**: Sign in as alice, call `/me`, advance the clock 15 min (`/me` returns 401), refresh, and call `/me` again (200).

### Tests (write first, must FAIL)

- [X] T014 [P] [US1] Unit tests for `SignInThrottle` in `test/unit/SignInThrottleTest.java` (`MutableClock`):
  - after failure N, the next attempt for that email is blocked for `min(2^(N-1), 30)` seconds, so 1, 2, 4, 8, 16, 30, 30 s;
  - a success resets the email's counter;
  - unknown emails are tracked the same way;
  - the 21st failure from one IP within an hour blocks that IP;
  - IP successes are not counted.
- [X] T015 [P] [US1] Unit tests for `AuthSessionService` in `test/unit/AuthSessionServiceTest.java` (Mockito):
  - `startSession` stores only hashes, and refresh expires at the earlier of +7 d and +30 d;
  - a refresh rotates the token: the old one is marked rotated and the new one is issued;
  - a rotated token presented again revokes the session (`REUSE`) and returns invalid;
  - `markRotatedIfCurrent` returning 0 is treated as reuse;
  - a token idle for 7 d → invalid;
  - refreshing on day 29 gives a token that expires at the 30-day cap; at the cap → invalid;
  - a revoked session → invalid;
  - a disabled account → invalid.
- [X] T016 [P] [US1] Unit tests for `SignInService` in `test/unit/SignInServiceTest.java`:
  - success resets the throttle and starts a session;
  - a wrong password, an unknown email and a DISABLED account all return the same `FAILED` outcome;
  - an unknown email still calls `passwordEncoder.matches` exactly once, against the dummy hash (research R6);
  - a throttled attempt returns `FAILED` without calling `matches`.
- [X] T017 [P] [US1] API tests in `test/api/SignInApiTest.java`:
  - success → `200` with `tokenType` `Bearer`, `expiresIn` 900, a 43-char `refreshToken` and a JWT `accessToken` whose header has `alg` ES256 and `kid`, and whose claims are `sub`/`sid`/`sv`/`iss` with no email;
  - every failure cause → a byte-identical `401 /problems/invalid-credentials` ("Email or password is incorrect.");
  - a malformed body → `400 /problems/validation`;
  - after 21 failed sign-ins from one IP, even the correct password → the identical 401;
  - per-email backoff end to end (FR-003b): 2 wrong passwords for alice, then the correct password immediately → the identical 401; advance `MutableClock` 2 s → the correct password → 200;
  - a padded, mixed-case email (` Alice@Example.COM `) signs in as alice (FR-001).
- [X] T018 [P] [US1] API tests in `test/api/RefreshApiTest.java`:
  - rotation: the old token → 401 and the new one → 200;
  - reuse of a rotated token → 401, and the session's newest token is also revoked;
  - two concurrent refreshes with the same token (`CountDownLatch`, two threads) → exactly one 200, after which the session is revoked;
  - 7 days idle → 401;
  - the 30-day cap → 401;
  - every failure → an identical `401 /problems/invalid-refresh-token`.
- [X] T019 [P] [US1] API tests in `test/api/MeApiTest.java`:
  - a valid token → `200 {id, email}`;
  - all of these → an identical `401` with `WWW-Authenticate: Bearer`: no header; expired (the clock advanced 15 min); a tampered signature; a wrong issuer; `alg` none or HS256; a stale `sv` (bump `session_version` via JDBC); a disabled account.

### Implementation

- [X] T020 [P] [US1] Create `main/ratelimit/SignInThrottle.java` (research R7):
  - per-email state is a consecutive-failure count and a `nextAllowedAt` instant, held in a `ConcurrentHashMap`;
  - the per-IP failure cap uses `RateLimiter.isLimited` / `record` with key `signin-ip:<ip>`, 20 per hour;
  - methods: `boolean allowed(email, ip)`, `void failure(email, ip)`, `void success(email)` and `reset()`.
- [X] T021 [US1] Add `Optional<IssuedCredentials> refresh(String rawRefreshToken)` to `main/service/auth/AuthSessionService.java`, implementing research R4 in one `TransactionTemplate`. A malformed token (`TokenGenerator.isWellFormed`) → empty with no lookup. Logs `auth.refresh outcome=`
- [X] T022 [US1] Create `main/service/auth/SignInService.java` with `SignInOutcome signIn(rawEmail, password, clientIp)`, a sealed `Success(IssuedCredentials)` or `FAILED`:
  1. Normalize the email.
  2. If the throttle does not allow the attempt → `FAILED`.
  3. Look up the account and run exactly one `matches`: against the real hash, or against a dummy BCrypt(12) hash computed at construction for an unknown email. Passwords over 72 bytes count as a mismatch without calling `matches`.
  4. Succeed only if `ACTIVE` and the password matches, then `startSession`.
  5. Record the result with the throttle.

  Logs `auth.sign_in outcome= reason=` with the account id only.
- [X] T023 [P] [US1] Create the DTOs in `main/web/api/dto/`: `SignInRequest(@NotBlank @Email @Size(max=254) email, @NotNull password)`, with the email stripped in a compact constructor and `toString` redacted; `RefreshRequest(@NotBlank refreshToken)`, redacted; `TokenResponse(accessToken, tokenType, expiresIn, refreshToken, refreshExpiresAt)`; and `MeResponse(id, email)`
- [X] T024 [US1] Create `main/web/api/AuthApiController.java` (`/api/auth`):
  - `POST /sign-in` → 200 `TokenResponse`, or 401 Problem `/problems/invalid-credentials`;
  - `POST /refresh` → 200, or 401 `/problems/invalid-refresh-token`;
  - `GET /me` (`@AuthenticationPrincipal Jwt`) → `MeResponse` via `AccountQueryService.findActive(sub)`. Create that one-method read service in `service/auth/`, so the controller makes no repository call (Constitution I).

  Add the new problem types to `ApiExceptionHandler`'s package scope (validation stays shared).

**Checkpoint**: T014–T019 pass, and the 001 suite is still green.

---

## Phase 4: User Story 2 - Sign up with email verification (P1)

**Goal**: Register with the email only (enumeration-safe), get a 24-hour single-use link, and choose the password on the page it opens; the account is created only then (prevents pre-registration takeover, research R8).

**Independent Test**: `POST /api/auth/register {"email":"carol@example.com"}`, extract the link from `FakeEmailSender`, open `GET /complete-registration?token=…`, submit a password, then sign in → 200.

### Tests (write first, must FAIL)

- [X] T025 [P] [US2] Unit tests for `RegistrationService` in `test/unit/RegistrationServiceTest.java`:
  - **no account**: older unused links for the email are invalidated, a new hashed 24-hour link is stored, a registration-link email is sent, and **no account** is created;
  - **ACTIVE email**: an "attempted registration" email with no link is sent and nothing is stored;
  - **DISABLED email**: nothing is sent or stored;
  - every branch returns the same outcome and never calls `passwordEncoder`;
  - `checkLink` never consumes the link;
  - `completeRegistration`: a valid link and password → ACTIVE account created and link used; 001 rule failures → violations, no account, link still usable; exactly at 24 h, used, invalidated, unknown or malformed → `INVALID`; the unique email constraint violation (a racing completion) → `INVALID`.
- [X] T026 [P] [US2] API tests in `test/api/RegisterApiTest.java`:
  - new, pending (a link already sent), active and disabled emails → a byte-identical `202 {"message":"Check your email to finish creating your account."}`;
  - the email captured per case is: a registration link for new and pending, a notice for active (subject "Someone tried to create an account with your email", no `complete-registration` link), and nothing for disabled;
  - an invalid email → `400 /problems/validation` and no email;
  - the 4th registration for one email within 15 min → the identical 202 with no email;
  - no `user_account` row exists after registering until the link is completed.
- [X] T027 [P] [US2] Page tests in `test/page/CompleteRegistrationPageTest.java`:
  - GET with a valid link → the form with the hidden token, and the link is not consumed (a following POST succeeds);
  - POST with a valid password → "Your account is ready. You can now sign in.", the account is ACTIVE, and sign-in with that password → 200;
  - POST with a rule failure → an inline error, the form is kept, and the link is still usable;
  - reused, expired (the clock advanced 24 h), superseded by a newer registration, unknown, malformed and missing tokens → "This link is invalid or has expired." on GET and POST;
  - two concurrent POSTs with two different valid links for the same email → exactly one account;
  - **takeover regression**: register `victim@example.com`; the victim opens the link and chooses their own password; an attacker cannot have chosen it at registration (`POST /api/auth/register` with any `password` field still creates nothing usable). Sign-in works only with the victim's chosen password;
  - every response has `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.

### Implementation

- [X] T028 [P] [US2] Create `main/mail/AuthEmailComposer.java` with `registrationLink(rawToken)` (a link to `${app.base-url}/complete-registration?token=…`, the text "expires in 24 hours", and "If you did not ask to create an account, ignore this email.") and `attemptedRegistration()` (offers only sign-in and `/forgot-password`, with no token). Add `@Async("mailExecutor")` `sendRegistrationLink(email, rawToken)` and `sendAttemptedRegistration(accountId, email)` to `main/mail/ResetMailDispatcher.java`, using the same failure logging (no email in logs)
- [X] T029 [US2] Create `main/service/auth/RegistrationService.java` (research R6, R8):
  - `register(rawEmail, clientIp)`:
    1. Normalize the email, and always generate and hash a token.
    2. Apply the 001 `RateLimiter` keys `register-email:` and `register-ip:` (3 per 15 min, 20 per hour); over the limit → same outcome.
    3. Branch on the account: none → `invalidateUnusedForEmail` + save the token + `AfterCommit` send link; ACTIVE → `AfterCommit` send notice; DISABLED → nothing.
    4. Log `auth.registered outcome=`.
  - `checkLink(rawToken)`, read-only, returns `USABLE` or `INVALID` (malformed → INVALID without a lookup).
  - `completeRegistration(rawToken, password, confirm)`, returning a sealed `Completed`, `Rejected(violations)` or `INVALID`:
    1. Resolve the usable token.
    2. Run 001 `PasswordPolicy.check(password, confirm, null)`.
    3. Encode the password.
    4. In one `TransactionTemplate`: `markUsedIfUsable == 1`, then save `UserAccount.active(email, hash, now)`.
    5. A `DataIntegrityViolationException` or 0 rows → `INVALID`.
    6. Log `auth.registration_completed accountId=`.
- [X] T030 [US2] Make `AfterCommit` public in `main/service/AfterCommit.java`, so `service/auth` can use it
- [X] T031 [P] [US2] Add DTO `RegisterRequest(@NotBlank @Email @Size(max=254) email)` (email stripped; unknown JSON fields such as `password` are ignored) and `POST /api/auth/register` → 202 to `AuthApiController`
- [X] T032 [P] [US2] Create `main/web/page/CompleteRegistrationPageController.java`, with `GET` and `POST /complete-registration`, an optional `token`, and the no-referrer and no-store headers. It maps outcomes to the templates `src/main/resources/templates/complete-registration.html` (form with hidden token and inline errors), `registration-complete.html` and `registration-invalid.html` (message plus a link to register again). Map messages like the 001 `PasswordResetPageController`

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
  - registration with a new vs. an existing (ACTIVE) email.

  Reset `SignInThrottle` and `RateLimiter` before each sample.
- [ ] T042 [P] Secrecy test `test/api/AuthSecrecyTest.java`. Use `OutputCaptureExtension` across register → complete registration → sign-in → refresh → change password → sign-out, and check:
  - logs contain no access token, refresh token, registration token, password or email, but do contain the `auth.*` event names;
  - every `refresh_token.token_hash` and `registration_token.token_hash` matches `^[0-9a-f]{64}$`, and no column contains a captured raw token.
- [ ] T043 [P] Update living docs:
  - `README.md`: the 002 row in the Documents table, the endpoints, `APP_JWT_PRIVATE_KEY`/`APP_JWT_KEY_ID` in the env table (with how to generate a P-256 PKCS#8 key using `openssl`), and the manual check steps for 002;
  - the `specs/001-reset-password/plan.md` Design Summary: note that reset now also revokes sessions (002 FR-017).
- [ ] T044 Run `./gradlew clean test` and `./gradlew test -PincludeTags=timing`. Then run the manual check with `bootRun` and `curl`: register, complete registration via the link in the captured mail, sign in, `/me`, refresh, change password, the old token → 401, sign-out. Append the results to this file
- [ ] T045 Constitution review: controllers in `web/` import no repository, mail or security internals beyond `Jwt`; every endpoint in plan.md › Interfaces has tests; refresh and registration tokens are hashed only; secrets come from env. Mark `tasks.md` frozen and set the spec Status to `Implemented`

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
