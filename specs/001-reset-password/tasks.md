---

description: "Task list for 001-reset-password (Forgot Password – Reset via Email)"
---

# Tasks: Forgot Password – Reset via Email

**Input**: Design documents from `/specs/001-reset-password/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: REQUIRED. Spec FR-017 and Constitution Principle III (NON-NEGOTIABLE) require automated tests for every endpoint and for the service rules. Each story writes its tests first and checks that they FAIL before implementing.

**Organization**: Tasks are grouped by user story so each story can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: The user story the task belongs to (US1–US4 from spec.md)

## Path Conventions

Single Gradle project at the repository root (plan.md › Project Structure):

- Main code: `src/main/java/com/example/resetpassword/`
- Resources: `src/main/resources/`
- Tests: `src/test/java/com/example/resetpassword/` (`unit/`, `api/`, `page/`, `support/`)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and build tooling

- [X] T001 Create the Gradle wrapper and `settings.gradle.kts` (rootProject.name = "reset-password-demo") at the repository root via `gradle wrapper --gradle-version 8.14.3`
- [X] T002 Create `build.gradle.kts` with Java 21 toolchain and Spring Boot 4.0.x plugin (fallback 3.5.x per research R1). Dependencies: spring-boot-starter-web, -thymeleaf, -validation, -data-jpa, -mail, `spring-security-crypto`, runtime `com.h2database:h2`, plus Flyway and test dependencies chosen by Boot line. On Boot 4.0.x, auto-configuration is modular: use the dedicated starters (`spring-boot-starter-flyway`, and the MockMvc test starter for Spring MVC, e.g. `spring-boot-starter-webmvc-test`, alongside `spring-boot-starter-test`). Verify the exact artifact names against the Boot 4 reference before writing. On 3.5.x, use `flyway-core` and `spring-boot-starter-test`. Confirm with a context-load smoke test that Flyway runs and `@AutoConfigureMockMvc` resolves. Configure JUnit Platform so tests tagged `timing` are excluded by default and included when `-PincludeTags=timing` is passed
- [X] T003 [P] Create `.gitignore` entries for `build/`, `.gradle/`, `data/` and `*.mv.db` at the repository root
- [X] T004 [P] Create `compose.yaml` at the repository root with a `mailpit` service (image `axllent/mailpit`, ports `1025:1025` SMTP and `8025:8025` UI) per research R10
- [X] T005 [P] Create the GitHub Actions workflow `.github/workflows/ci.yml` (Constitution III: tests must pass in CI before merge). It triggers on `push` and `pull_request` to `main`, uses `actions/setup-java` with Temurin 21 and the Gradle cache, and has two jobs: `test`, which runs `./gradlew test`, and `timing`, which runs `./gradlew test -PincludeTags=timing` for SC-004 (separate so a noisy runner fails only that job). Both jobs are required checks
- [X] T006 Create the application entry point `src/main/java/com/example/resetpassword/ResetPasswordApplication.java` with `@SpringBootApplication` and `@EnableAsync`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuration, schema, entities, ports and test support that ALL stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T007 Create `src/main/resources/application.yml`. Settings: H2 file datasource `jdbc:h2:file:./data/resetpassword`, `spring.jpa.hibernate.ddl-auto=validate`, Flyway enabled, `spring.mail.host/port` defaulting to `localhost`/`1025` (overridable by `SPRING_MAIL_*` env vars), `app.base-url: ${APP_BASE_URL:http://localhost:8080}`, `app.mail-from: ${APP_MAIL_FROM:no-reply@example.com}`, Tomcat access log disabled, H2 console disabled, `server.forward-headers-strategy: none` (research R7)
- [X] T008 [P] Create `src/test/resources/application-test.yml` with in-memory H2 `jdbc:h2:mem:test;DB_CLOSE_DELAY=-1`
- [X] T009 [P] Create `src/main/resources/db/migration/V1__schema.sql`:
  - table `user_account`: `id` BIGINT PK identity; `email` VARCHAR(254) NOT NULL UNIQUE ("Stored normalized: trimmed and lower-cased"); `password_hash` VARCHAR(100) NOT NULL; `status` VARCHAR(16) NOT NULL (`ACTIVE` or `DISABLED`); `created_at`, `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL.
  - table `password_reset_token`: `id` BIGINT PK identity; `account_id` BIGINT NOT NULL FK → `user_account.id` with index `(account_id)`; `token_hash` CHAR(64) NOT NULL UNIQUE ("Lowercase hex SHA-256 of the raw token"); `issued_at`, `expires_at` TIMESTAMP WITH TIME ZONE NOT NULL; `used_at`, `invalidated_at` TIMESTAMP WITH TIME ZONE NULL.
- [X] T010 [P] Create `src/main/resources/db/migration/V2__seed_accounts.sql` seeding `alice@example.com` (ACTIVE), `bob@example.com` (ACTIVE) and `disabled@example.com` (DISABLED), all with the BCrypt (strength 12) hash of `demo-password-1`
- [X] T011 [P] Create `src/main/java/com/example/resetpassword/config/AppProperties.java` (`@ConfigurationProperties("app")` with `baseUrl` and `mailFrom`) and register it in `ResetPasswordApplication`
- [X] T012 [P] Create `src/main/java/com/example/resetpassword/config/CoreBeansConfig.java` exposing `Clock` (`Clock.systemUTC()`), `PasswordEncoder` (`BCryptPasswordEncoder(12)`), and a bounded `ThreadPoolTaskExecutor` named `mailExecutor` (core 2, max 4, queue 100) for async email (research R5)
- [X] T013 [P] Create the `UserAccount` JPA entity in `src/main/java/com/example/resetpassword/domain/UserAccount.java` (fields per data-model.md; `AccountStatus` enum `ACTIVE`/`DISABLED` in `domain/AccountStatus.java`), and `UserAccountRepository` with `Optional<UserAccount> findByEmail(String normalizedEmail)` in `src/main/java/com/example/resetpassword/repository/UserAccountRepository.java`
- [X] T014 [P] Create the `PasswordResetToken` JPA entity in `src/main/java/com/example/resetpassword/domain/PasswordResetToken.java` (fields per data-model.md; method `isUsable(Instant now)` = `usedAt == null && invalidatedAt == null && now.isBefore(expiresAt)`) and `PasswordResetTokenRepository` in `src/main/java/com/example/resetpassword/repository/PasswordResetTokenRepository.java`:
  - `findByTokenHash`;
  - `@Modifying` bulk `invalidateUnusedForAccount(accountId, now)`, which sets `invalidated_at = now` where `used_at IS NULL AND invalidated_at IS NULL`;
  - `@Modifying` `markUsedIfUsable(id, now)` returning the updated row count, with condition `used_at IS NULL AND invalidated_at IS NULL AND expires_at > :now` (research R8).
- [X] T015 [P] Create `TokenGenerator` in `src/main/java/com/example/resetpassword/security/TokenGenerator.java`: `generate()` returns 32 bytes from `SecureRandom` as Base64URL without padding (43 chars); `hash(String raw)` returns lowercase hex SHA-256 (64 chars); `isWellFormed(String raw)` checks the 43-char Base64URL pattern (research R3)
- [X] T016 [P] Unit test `TokenGenerator` in `src/test/java/com/example/resetpassword/unit/TokenGeneratorTest.java`: length 43, Base64URL alphabet only, 1,000 generated tokens are unique, hash is 64 lowercase hex chars and deterministic, `isWellFormed` rejects wrong length or characters
- [X] T017 [P] Create the `EmailSender` port `src/main/java/com/example/resetpassword/mail/EmailSender.java` (`void send(String to, String subject, String body)`) and `SmtpEmailSender` adapter using `JavaMailSender` and `AppProperties.mailFrom` in `src/main/java/com/example/resetpassword/mail/SmtpEmailSender.java`
- [X] T018 [P] Create test support:
  - `FakeEmailSender` (thread-safe captured list, `clear()`, `sentTo(email)`) in `src/test/java/com/example/resetpassword/support/FakeEmailSender.java`;
  - `MutableClock` (settable/advanceable `Clock`) in `src/test/java/com/example/resetpassword/support/MutableClock.java`;
  - `TestBeansConfig` (`@TestConfiguration` that replaces `EmailSender` with the fake, `Clock` with `MutableClock`, and `mailExecutor` with a synchronous executor so email assertions are deterministic) in `src/test/java/com/example/resetpassword/support/TestBeansConfig.java`. Spring Boot forbids bean-definition overriding by default, so declare each replacement as a `@Primary` bean with a distinct method name, and do not enable `spring.main.allow-bean-definition-overriding`. `mailExecutor` is resolved by qualifier, so instead of replacing the bean, `CoreBeansConfig` returns a `SyncTaskExecutor` when `app.mail-async=false` (set in `application-test.yml`). This was changed during implementation: `@ConditionalOnMissingBean` on a regular `@Configuration` depends on processing order and is unreliable.
- [X] T019 Create `IntegrationTestBase` in `src/test/java/com/example/resetpassword/support/IntegrationTestBase.java`. It is an abstract class annotated `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")` and `@Import(TestBeansConfig.class)`, and exposes `MockMvc`, `FakeEmailSender`, `MutableClock` and `JdbcTemplate`. Its `@BeforeEach` isolates shared state between tests:
  1. Set `MutableClock` to the fixed instant `2026-01-01T00:00:00Z`.
  2. `FakeEmailSender.clear()`.
  3. Call `reset()` on every `RateLimiter` bean via `ObjectProvider<RateLimiter>`. This is a no-op until US3 adds the limiter.
  4. `DELETE FROM password_reset_token`.
  5. Restore every seeded account's `password_hash` and `status` to the V2 seed values.

  ALL tests under `api/` and `page/` MUST extend this class.
- [X] T020 Create `ApiExceptionHandler` (`@RestControllerAdvice` for `web.api` only) in `src/main/java/com/example/resetpassword/web/api/ApiExceptionHandler.java`. It maps bean-validation failures to RFC 9457 Problem `type: /problems/validation` with `errors[{field, message}]`, never echoes request bodies, and logs no input values (FR-015)
- [X] T021 Create `ResetEmailComposer` in `src/main/java/com/example/resetpassword/mail/ResetEmailComposer.java`. It builds the reset email (subject `Reset your password`, link `${app.base-url}/reset-password?token=<raw>`, the text "expires in 15 minutes and can be used once", and "If you did not request this, you can ignore this email.") and the changed-password email (subject `Your password was changed`, UTC time of change, no link or token), per contracts/emails.md
- [X] T022 Create `ResetMailDispatcher` in `src/main/java/com/example/resetpassword/mail/ResetMailDispatcher.java`. It has `@Async("mailExecutor")` methods `sendResetLink(email, rawToken)` and `sendPasswordChanged(email, changedAt)` that call `ResetEmailComposer` and `EmailSender`, catch every exception, and log `password_reset.email_failed` with the account id only (research R5, R9)

**Checkpoint**: The app starts (`./gradlew bootRun`), Flyway applies V1/V2, and `TokenGeneratorTest` passes.

---

## Phase 3: User Story 1 - Request a password reset link (Priority: P1) 🎯 MVP

**Goal**: A user submits their email (API or page) and an ACTIVE account receives one email with a reset link. The token is stored only as a hash, and previous unused links are invalidated.

**Independent Test**: Submit `alice@example.com`. Expect the generic confirmation and exactly one email captured by `FakeEmailSender` whose link token hashes to a stored `token_hash`.

### Tests for User Story 1 ⚠️ (write first, must FAIL)

- [ ] T023 [P] [US1] Unit tests for `PasswordResetService.requestReset` in `src/test/java/com/example/resetpassword/unit/PasswordResetServiceRequestTest.java`:
  - an ACTIVE account gets a token with `expires_at = issued_at + 15 min` and only its hash stored (never the raw token);
  - the email is normalized (` A@Example.com ` → `a@example.com`);
  - a second request sets `invalidated_at` on the first token (FR-008);
  - a DISABLED account and an unknown email issue no token and send no email;
  - the return value is the same `RequestOutcome.ACCEPTED` in all cases.
- [ ] T024 [P] [US1] API tests for `POST /api/password-reset/requests` in `src/test/java/com/example/resetpassword/api/RequestResetApiTest.java` (extends `IntegrationTestBase`):
  - known email → 202 with body `{"message":"If an account exists for this email, a password reset link has been sent."}` and 1 email captured whose link contains a 43-char token;
  - mixed-case or padded email → same account;
  - missing or invalid email → 400 `application/problem+json` with `type /problems/validation`, field `email`, and no email sent.
- [ ] T025 [P] [US1] Page tests for `GET/POST /forgot-password` in `src/test/java/com/example/resetpassword/page/ForgotPasswordPageTest.java`: GET renders the form. A POST with a valid email renders the confirmation text "If an account exists for this email, a password reset link has been sent." and sends 1 email. An invalid email renders an inline field error and sends no email. Enumeration check: POSTs for `alice@example.com`, `nobody@example.com` and `disabled@example.com` return the same status and the same rendered message element (compare the extracted confirmation text and form markup, ignoring the echoed input value), and only the known email produces a captured message

### Implementation for User Story 1

- [ ] T026 [US1] Create `PasswordResetService` in `src/main/java/com/example/resetpassword/service/PasswordResetService.java` with `RequestOutcome requestReset(String rawEmail, String clientIp)`, implemented as one `@Transactional` call:
  1. Normalize the email (trim + `toLowerCase(Locale.ROOT)`).
  2. Always generate and hash a token.
  3. Look up the account; only if it is ACTIVE: invalidate its unused tokens, save the new `PasswordResetToken` (`issuedAt = clock.instant()`, `expiresAt = issuedAt + 15 min`), and register `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { afterCommit() → ResetMailDispatcher.sendResetLink(...) })`, so no email is sent if the transaction rolls back.
  4. Return `ACCEPTED`.

  Log `password_reset.requested` with the outcome and account id only. Leave the `clientIp` parameter unused for now; US3 adds rate limiting.
- [ ] T027 [P] [US1] Create the request DTOs `RequestResetRequest` (`@NotBlank @Email @Size(max=254) email`) and `MessageResponse` (`message`) in `src/main/java/com/example/resetpassword/web/api/dto/`
- [ ] T028 [US1] Create `PasswordResetApiController` in `src/main/java/com/example/resetpassword/web/api/PasswordResetApiController.java` with `POST /api/password-reset/requests`: `@Valid` body → `service.requestReset(email, request.getRemoteAddr())` → `202` with the fixed message. No other logic (Constitution I)
- [ ] T029 [P] [US1] Create the Thymeleaf template `src/main/resources/templates/forgot-password.html`: an email form posting to `/forgot-password`, an inline error slot, and a confirmation message slot
- [ ] T030 [US1] Create `PasswordResetPageController` in `src/main/java/com/example/resetpassword/web/page/PasswordResetPageController.java` with `GET /forgot-password` (render form) and `POST /forgot-password` (`@Valid` form object → `service.requestReset(email, request.getRemoteAddr())` → render the same template with the confirmation, or with the field error on validation failure). No other logic

**Checkpoint**: T023–T025 pass. US1 is demoable via the Mailpit quickstart steps 1–2.

---

## Phase 4: User Story 2 - Set a new password using the link (Priority: P1)

**Goal**: A usable link lets the user set a new password that satisfies the rules. The password update and marking the token used happen in one atomic step, and a "password changed" email is sent.

**Independent Test**: Seed a token directly (save a `PasswordResetToken` with `TokenGenerator.hash(raw)`), POST a valid new password, and expect success. `CredentialService.verifyCredentials` accepts the new password and rejects `demo-password-1`.

### Tests for User Story 2 ⚠️ (write first, must FAIL)

- [ ] T031 [P] [US2] Unit tests for `PasswordPolicy` in `src/test/java/com/example/resetpassword/unit/PasswordPolicyTest.java`:
  - 7 chars → `TOO_SHORT`; 8 chars OK; 64 chars OK; 65 chars → `TOO_LONG`;
  - a 64-code-point password over 72 UTF-8 bytes → `TOO_LONG`;
  - no character-class rule (e.g. "aaaaaaaa" is OK);
  - confirmation differs → `MISMATCH`;
  - same as current hash → `SAME_AS_CURRENT`.
- [ ] T032 [P] [US2] Unit tests for `PasswordResetService.checkToken/resetPassword` in `src/test/java/com/example/resetpassword/unit/PasswordResetServiceResetTest.java`:
  - success updates `password_hash` (BCrypt), sets `used_at`, and dispatches one changed-password email;
  - a policy failure returns the violation, does not consume the token, and leaves the password unchanged;
  - `checkToken` on a usable token returns `USABLE` and does not consume it.
- [ ] T033 [P] [US2] Unit tests for `CredentialService.verifyCredentials` in `src/test/java/com/example/resetpassword/unit/CredentialServiceTest.java`: the correct password returns true; a wrong password, an unknown email or a DISABLED account returns false; the email is normalized (` Alice@Example.com `)
- [ ] T034 [P] [US2] API tests for `POST /api/password-reset/confirm` in `src/test/java/com/example/resetpassword/api/ConfirmResetApiTest.java`:
  - valid → 200 `{"message":"Your password has been reset."}`;
  - afterwards `CredentialService.verifyCredentials` accepts the new password and rejects `demo-password-1`;
  - each rule failure → 400 `type /problems/validation` with the `code` (`TOO_SHORT`/`TOO_LONG`/`SAME_AS_CURRENT`/`MISMATCH`), and the token still works for a following valid request;
  - 1 changed-password email captured, containing no token.
- [ ] T035 [P] [US2] Page tests for `GET/POST /reset-password` in `src/test/java/com/example/resetpassword/page/ResetPasswordPageTest.java`:
  - a GET with a usable token renders the form with a hidden `token` field and does not consume it (a following POST succeeds);
  - a POST with a valid password renders `reset-success`;
  - a rule failure re-renders the form with an inline error and the token kept;
  - responses include `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.

### Implementation for User Story 2

- [ ] T036 [P] [US2] Create `PasswordPolicy` and the `PasswordViolation` enum (`TOO_SHORT`, `TOO_LONG`, `SAME_AS_CURRENT`, `MISMATCH`) in `src/main/java/com/example/resetpassword/service/PasswordPolicy.java`. Rules: "8–64 Unicode code points and ≤ 72 UTF-8 bytes; must not match the current password"; "confirmPassword must equal newPassword" (data-model.md)
- [ ] T037 [US2] Add the following to `PasswordResetService` in `src/main/java/com/example/resetpassword/service/PasswordResetService.java`:
  - `TokenStatus checkToken(String rawToken)`: read-only; returns `USABLE` or `INVALID_OR_EXPIRED`.
  - `ResetOutcome resetPassword(String rawToken, String newPassword, String confirmPassword)`, `@Transactional`:
    1. Resolve the token by hash. Not found or not usable → `INVALID_OR_EXPIRED`.
    2. Run `PasswordPolicy`. Violations → return them without consuming the token.
    3. `markUsedIfUsable(id, now)`. If 1 row was updated, set the BCrypt `password_hash` and `updated_at`, then register an `afterCommit` synchronization (same mechanism as in the US1 `requestReset` task) that dispatches the changed-password email. If 0 rows → `INVALID_OR_EXPIRED`.

  Define the result types in `service/ResetOutcome.java` and `service/TokenStatus.java`. Log `password_reset.completed` / `password_reset.rejected` with the account id and reason only.
- [ ] T038 [P] [US2] Create `CredentialService` in `src/main/java/com/example/resetpassword/service/CredentialService.java` with `boolean verifyCredentials(String rawEmail, String password)`. It normalizes the email, finds an ACTIVE account, and returns `passwordEncoder.matches`. This is the spec's "credential check"; there is no HTTP endpoint (sign-in is out of scope)
- [ ] T039 [P] [US2] Create DTO `ConfirmResetRequest` (`@NotBlank token`, `@NotNull newPassword`, `@NotNull confirmPassword`) in `src/main/java/com/example/resetpassword/web/api/dto/ConfirmResetRequest.java`
- [ ] T040 [US2] Add `POST /api/password-reset/confirm` to `src/main/java/com/example/resetpassword/web/api/PasswordResetApiController.java`. It maps `ResetOutcome` as: success → 200 message; violations → 400 Problem `/problems/validation` with `errors[{field, code, message}]`; `INVALID_OR_EXPIRED` → 400 Problem `/problems/invalid-reset-token` with title "This link is invalid or has expired."
- [ ] T041 [P] [US2] Create templates `src/main/resources/templates/reset-password.html` (new/confirm password fields, hidden `token`, inline error slot), `src/main/resources/templates/reset-success.html` ("Your password has been reset.") and `src/main/resources/templates/reset-invalid.html` ("This link is invalid or has expired." with a "Request a new link" anchor to `/forgot-password`)
- [ ] T042 [US2] Add `GET /reset-password?token=` (`service.checkToken` → `reset-password` or `reset-invalid`) and `POST /reset-password` (→ `service.resetPassword` → `reset-success`, `reset-password` with error, or `reset-invalid`) to `src/main/java/com/example/resetpassword/web/page/PasswordResetPageController.java`. Set the `Referrer-Policy: no-referrer` and `Cache-Control: no-store` headers on both

**Checkpoint**: T031–T035 pass. US1 + US2 form the full happy path (quickstart steps 1–4).

---

## Phase 5: User Story 3 - Prevent account enumeration (Priority: P1)

**Goal**: Unknown, disabled and rate-limited requests are indistinguishable from known ones in message, status, structure and timing. Rate limits are enforced (FR-014).

**Independent Test**: POST a known and an unknown email. Bodies, status and content type are byte-identical, and only the known email produces a captured message.

### Tests for User Story 3 ⚠️ (write first, must FAIL)

- [ ] T043 [P] [US3] Unit tests for `InMemorySlidingWindowRateLimiter` in `src/test/java/com/example/resetpassword/unit/RateLimiterTest.java` using `MutableClock`:
  - 3 allowed then the 4th denied for `email:` within 15 min, and allowed again once the oldest entry leaves the window;
  - 20 allowed then the 21st denied for `ip:` within 60 min;
  - keys are independent;
  - concurrent `tryAcquire` from 10 threads never exceeds the limit.
- [ ] T044 [P] [US3] API enumeration tests in `src/test/java/com/example/resetpassword/api/EnumerationApiTest.java`:
  - known vs unknown vs `disabled@example.com` → identical status, `Content-Type` and body bytes;
  - no email for the unknown or disabled address;
  - the 4th request for the same email within 15 min → identical 202 with no new email;
  - the 21st request from the same IP within 1 h (vary emails) → identical 202 with no email;
  - an unknown email also consumes rate-limit slots.
- [ ] T045 [P] [US3] Timing test (SC-004) tagged `@Tag("timing")` in `src/test/java/com/example/resetpassword/api/EnumerationTimingTest.java`. After a warm-up, measure 50 requests each for known and unknown emails (resetting the rate limiter between runs) and assert that the medians differ by ≤ 50 ms

### Implementation for User Story 3

- [ ] T046 [P] [US3] Create the `RateLimiter` interface (`boolean tryAcquire(String key, int limit, Duration window)`) and `InMemorySlidingWindowRateLimiter` (`ConcurrentHashMap<String, Deque<Instant>>`, per-key synchronization, injected `Clock`, evicts stale timestamps on access, `reset()` for tests) in `src/main/java/com/example/resetpassword/ratelimit/`
- [ ] T047 [US3] Integrate rate limiting into `PasswordResetService.requestReset` in `src/main/java/com/example/resetpassword/service/PasswordResetService.java`:
  1. After normalization and before the account lookup, check `email:<normalized>` (3 per 15 min) and `ip:<clientIp>` (20 per 60 min). Both are always evaluated.
  2. If either is denied, issue no token and send no email, log the `rate_limited` outcome, and still return `ACCEPTED`.
  3. Keep the token generate+hash step on every path so the work stays symmetric (research R5).

**Checkpoint**: T043–T045 pass, and US1/US2 tests still pass.

---

## Phase 6: User Story 4 - Reject expired or already-used links (Priority: P2)

**Goal**: Expired (at or after 15:00), used, invalidated, unknown and malformed links are all rejected the same way. Concurrent double submits succeed at most once.

**Independent Test**: Use a link once and then reuse it; separately, advance `MutableClock` by exactly 15 minutes. Both are rejected with the identical "invalid or expired" response and the password is unchanged.

### Tests for User Story 4 ⚠️ (write first, must FAIL)

- [ ] T048 [P] [US4] Unit tests for token rejection in `src/test/java/com/example/resetpassword/unit/PasswordResetServiceRejectionTest.java`:
  - at `issued_at + 14:59.999` → usable; at exactly `issued_at + 15:00` → `INVALID_OR_EXPIRED`;
  - used → invalid; invalidated by a newer token → invalid;
  - unknown and malformed (wrong length or characters) → invalid without a DB lookup for the malformed case;
  - the password is unchanged in every case.
- [ ] T049 [P] [US4] API rejection tests in `src/test/java/com/example/resetpassword/api/InvalidTokenApiTest.java`: expired, reused, invalidated, unknown and malformed tokens all return a byte-identical 400 Problem `/problems/invalid-reset-token`. Token checks take precedence over password-rule errors (an invalid token with a too-short password still gets `invalid-reset-token`)
- [ ] T050 [P] [US4] Concurrency test in `src/test/java/com/example/resetpassword/api/ConcurrentConfirmApiTest.java`: two threads submit a valid confirm with the same token at the same time (`CountDownLatch`). Exactly one 200 and one 400 `invalid-reset-token`, and exactly one changed-password email
- [ ] T051 [P] [US4] Page rejection tests in `src/test/java/com/example/resetpassword/page/InvalidLinkPageTest.java`: GET and POST `/reset-password` with expired, used, invalidated, unknown, malformed or missing `token` render `reset-invalid` with the text "This link is invalid or has expired." and a link to `/forgot-password`

### Implementation for User Story 4

- [ ] T052 [US4] Harden token resolution in `src/main/java/com/example/resetpassword/service/PasswordResetService.java`. In both `checkToken` and `resetPassword`, first check `TokenGenerator.isWellFormed` (malformed → `INVALID_OR_EXPIRED` without a lookup). Resolve the token with `isUsable(clock.instant())` before running `PasswordPolicy`. Map every failure reason to the single `INVALID_OR_EXPIRED` outcome, keeping the reason only in the log
- [ ] T053 [US4] Make `PasswordResetPageController` in `src/main/java/com/example/resetpassword/web/page/PasswordResetPageController.java` render `reset-invalid` for a missing or blank `token` parameter on GET and POST, instead of a framework 400 page

**Checkpoint**: T048–T051 pass and all earlier tests still pass. SC-002 is verified.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Verify the logging and secrecy guarantees, documentation, and final validation

- [ ] T054 [P] Log secrecy test in `src/test/java/com/example/resetpassword/api/LogSecrecyTest.java`. It captures logs (`OutputCaptureExtension`) across a full request → confirm → reuse flow and asserts that the output never contains the raw token, the new or old password, or the email address, and does contain the event names `password_reset.requested`, `password_reset.completed` and `password_reset.rejected` (FR-015, research R9)
- [ ] T055 [P] Storage secrecy test in `src/test/java/com/example/resetpassword/api/TokenStorageTest.java`. After a request, every `password_reset_token.token_hash` matches `^[0-9a-f]{64}$` and no column contains the raw token captured from the email (SC-006)
- [ ] T056 [P] Email failure test in `src/test/java/com/example/resetpassword/api/EmailFailureApiTest.java`. With a throwing `EmailSender`, `POST /api/password-reset/requests` for a known email still returns the identical 202, and `password_reset.email_failed` is logged with no email or token in the output
- [ ] T057 [P] Email hand-off latency test (SC-005) in `src/test/java/com/example/resetpassword/api/EmailHandoffLatencyTest.java`. It extends `IntegrationTestBase` but overrides `mailExecutor` with the real bounded executor (via `@TestPropertySource` or a nested `@TestConfiguration`). It sends 20 reset requests for known accounts and records the time from the HTTP response to `FakeEmailSender` capture, then asserts p95 ≤ 5 s
- [ ] T058 [P] Create `README.md` at the repository root: a project summary, a link to `specs/001-reset-password/quickstart.md`, the env var table, and the known limitations (in-memory rate limiting, forward headers)
- [ ] T059 Run `./gradlew test` and `./gradlew test -PincludeTags=timing`, then run quickstart.md manual steps 1–7 against Mailpit and record the results in the PR description
- [ ] T060 Constitution compliance review. Check that no controller in `src/main/java/com/example/resetpassword/web/` contains business branching or repository/mail calls (I–II); that every endpoint in contracts/ has tests (III); and that tokens and passwords are only hashed and never logged (IV)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup, and BLOCKS all user stories.
- **US1 (Phase 3)**: depends on Foundational.
- **US2 (Phase 4)**: depends on Foundational. It is independent of US1 because its tests seed tokens directly, but it shares `PasswordResetService.java` and `PasswordResetApiController.java`, so the edits are sequential if done by one developer.
- **US3 (Phase 5)**: depends on US1 (it extends `requestReset`).
- **US4 (Phase 6)**: depends on US2 (it hardens `checkToken`/`resetPassword` and the reset pages).
- **Polish (Phase 7)**: depends on all stories.

### Story Completion Order

```
Setup → Foundational → US1 ─┬─▶ US3
                            │
              (Foundational)└─▶ US2 ─▶ US4 → Polish
```

### Within Each Story

- Tests are written first and must FAIL before the implementation.
- DTOs/policy → service → controller → templates.
- Shared files (`PasswordResetService.java`, `PasswordResetApiController.java`, `PasswordResetPageController.java`) are edited in task order; they are never marked [P] against each other.

### Parallel Opportunities

- Setup: T003, T004 and T005 can run in parallel.
- Foundational: T008–T018 touch separate files and can run in parallel after T007.
- All test tasks within a story are marked [P].
- US1 and US2 can proceed in parallel by different developers once Foundational is done (coordinate on the shared service and controller files).

---

## Parallel Example: User Story 1

```bash
# Tests first (parallel):
Task: "T023 Unit tests for PasswordResetService.requestReset in src/test/java/com/example/resetpassword/unit/PasswordResetServiceRequestTest.java"
Task: "T024 API tests for POST /api/password-reset/requests in src/test/java/com/example/resetpassword/api/RequestResetApiTest.java"
Task: "T025 Page tests for /forgot-password in src/test/java/com/example/resetpassword/page/ForgotPasswordPageTest.java"

# Then, in parallel:
Task: "T027 Request DTOs in src/main/java/com/example/resetpassword/web/api/dto/"
Task: "T029 Template src/main/resources/templates/forgot-password.html"
```

## Parallel Example: User Story 2

```bash
Task: "T031 PasswordPolicyTest"   Task: "T032 PasswordResetServiceResetTest"   Task: "T033 CredentialServiceTest"
Task: "T034 ConfirmResetApiTest"  Task: "T035 ResetPasswordPageTest"
Task: "T036 PasswordPolicy"       Task: "T038 CredentialService"   Task: "T039 ConfirmResetRequest DTO"   Task: "T041 reset-*.html templates"
```

---

## Implementation Strategy

### MVP First

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 (US1) + Phase 4 (US2): the smallest version of the feature that actually lets a user recover an account.
3. **STOP and VALIDATE**: quickstart steps 1–4.
4. Phase 5 (US3) before any real exposure. It is a P1 security requirement, so shipping without it would leak which accounts exist.

### Incremental Delivery

1. Setup + Foundational → the app boots with seeded accounts.
2. + US1 → links can be requested (demo the emails in Mailpit).
3. + US2 → full reset flow.
4. + US3 → enumeration-safe and rate limited.
5. + US4 → hardened rejection and concurrency safety.
6. Polish → secrecy tests, README, final validation.

---

## Notes

- [P] tasks touch different files and have no dependency on incomplete tasks.
- The [USn] labels map tasks to the spec's user stories for traceability.
- Commit after each task or logical group, and stop at any checkpoint to validate.
- The tasks template marks tests as optional; they are mandatory here per Constitution III (see the constitution's Sync Impact Report).
