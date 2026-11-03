# Research: Forgot Password – Reset via Email

All Technical Context unknowns are resolved below. The stack (Java + Spring Boot) was
chosen by the project owner on 2026-11-03.

## R1. Framework version

- **Decision**: Java 21 and Spring Boot 4.0.x (latest stable patch when implementation starts), built with Gradle (Kotlin DSL) and the Gradle wrapper.
- **Rationale**: Java 21 is the current LTS and is installed in the dev environment. Boot 4.0 is the supported line; 3.5.x OSS support is ending. The design uses only stable APIs (MVC, Thymeleaf, JPA, Mail, Validation), so a downgrade to 3.5.x is a version change in the build file, not a design change.
- **Alternatives considered**: Spring Boot 3.5.x (fallback if a dependency is not yet 4.0-compatible). Maven (equivalent; Gradle is already installed and the wrapper avoids version drift).

## R2. Storage and seed data

- **Decision**: H2 database via Spring Data JPA. The schema and the seeded accounts come from Flyway migrations. Tests use in-memory H2; local runs use file mode in `./data/` (git-ignored).
- **Rationale**: H2 needs no external service, so the demo and CI just work. Flyway gives a versioned schema and a natural place for seed data (spec: accounts exist only as seeded records).
- **Alternatives considered**: PostgreSQL via Docker (more realistic, but adds an infrastructure dependency the spec doesn't need). In-memory maps (can't express the atomic single-use update, and wouldn't exercise real persistence).

## R3. Reset token generation and storage

- **Decision**: Generate 32 bytes from `SecureRandom` and encode them as Base64URL without padding (43 characters) for the link. Persist only the `SHA-256(token)` hex digest in `token_hash` (unique index). Look up by hash.
- **Rationale**: 256 bits of entropy can't be guessed or brute-forced, which meets FR-009. A fast hash is enough because the input is high-entropy, unlike a password, and it allows indexed lookup. A database leak doesn't reveal usable links (FR-010, Constitution IV).
- **Alternatives considered**: BCrypt for tokens (slow and can't be looked up by an index; unnecessary for random tokens). Signed JWT (stateless, so it can't enforce single use or invalidation without server state anyway).

## R4. Password hashing and policy

- **Decision**: `spring-security-crypto` `BCryptPasswordEncoder` (strength 12) for stored passwords. `PasswordPolicy` in the service layer checks length 8–64, a matching confirmation, and that the password differs from the current one (`encoder.matches(new, currentHash)`). Length is counted in Unicode code points.
- **Rationale**: BCrypt is slow and salted (Constitution IV). Its 72-byte input limit is above the 64-character maximum for typical input; a 64-character password made of 2-byte characters could exceed 72 bytes, so the limit is applied as max 64 characters **and** max 72 UTF-8 bytes, with the same error message. Only the crypto module is used: no filter chain, sessions, or login page.
- **Alternatives considered**: Argon2 (needs BouncyCastle; BCrypt is sufficient for the demo). Full Spring Security (brings sessions and CSRF configuration the spec explicitly excludes).

## R5. Preventing enumeration, including timing (FR-005, SC-003, SC-004)

- **Decision**: The request endpoint always returns `202 Accepted` with one fixed message. The service does identical work on every path up to the account lookup: validate, normalize, run the rate-limit check, and generate plus hash a token. Sending email is dispatched to a bounded async executor (`@Async`), so the HTTP response never waits for SMTP. Email-send failures are logged (by account id only) and never change the response.
- **Rationale**: Waiting for SMTP is the main cause of a timing difference between known and unknown emails. Moving it off the request path and keeping the CPU work symmetric keeps the median difference well under 50 ms.
- **Alternatives considered**: An artificial fixed delay (adds latency for everyone and is fragile). A synchronous send with a random delay (still leaks on average).

## R6. Rate limiting (FR-014)

- **Decision**: A `RateLimiter` interface with an `InMemorySlidingWindowRateLimiter` that keeps timestamps per key in a `ConcurrentHashMap`. Two rules: key `email:<normalized>` allows 3 per 15 minutes, key `ip:<addr>` allows 20 per 60 minutes. It uses the injected `Clock`. The check runs before the account lookup and consumes a slot for unknown emails too (otherwise throttling behavior would differ between known and unknown emails). Over-limit requests get the same 202 response and issue no token and no email. Stale keys are evicted on access.
- **Rationale**: This needs no dependency and is deterministic with a test clock. It works because the target is a single instance.
- **Alternatives considered**: Bucket4j (a token bucket, not an exact "N per window" match; adds a dependency). Counting rows in the database (doesn't cover per-IP limits for unknown emails).
- **Known limitation**: Limits are per instance and reset on restart. This is acceptable for the demo and documented in the quickstart.

## R7. Client IP

- **Decision**: Use `HttpServletRequest.getRemoteAddr()`. `server.forward-headers-strategy` stays off by default and can be enabled by config when deployed behind a trusted proxy.
- **Rationale**: Trusting `X-Forwarded-For` without a trusted proxy lets attackers bypass the IP limit.

## R8. Single use, invalidation and concurrency (FR-007, FR-008, FR-013)

- **Decision**:
  - When a token is issued, in one transaction: mark all of the account's unused, non-invalidated tokens `invalidated_at = now`, then insert the new token.
  - When the password is reset, in one transaction: run a conditional update `UPDATE password_reset_token SET used_at = :now WHERE id = :id AND used_at IS NULL AND invalidated_at IS NULL AND expires_at > :now`. Only if exactly 1 row was updated, update the password hash. Otherwise roll back and return `INVALID_OR_EXPIRED`.
- **Rationale**: The database condition makes concurrent double submits safe without locks held across requests. The validity boundary is strict (`expires_at > now`), which matches the edge case that a link is valid only while *less than* 15 minutes have elapsed.

## R9. Logging and observability (FR-015)

- **Decision**: Structured log events `password_reset.requested` (outcome: issued / unknown_or_inactive / rate_limited), `password_reset.completed`, `password_reset.rejected` (reason) and `password_reset.email_failed`. Events include the account id when one is known, never the email, token or password. The token is passed only as a query parameter to the reset page and is excluded from access logs (embedded Tomcat access logging stays disabled). Exception messages never include request bodies.
- **Rationale**: Operators can audit and spot abuse (spec edge case: "failure is recorded for operators") without logging PII or secrets.

## R10. Email delivery (FR-018, FR-016)

- **Decision**: An `EmailSender` port with an `SmtpEmailSender` adapter (`JavaMailSender`, configured by `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`). Tests use a `FakeEmailSender` bean that captures messages in memory. Local runs use Mailpit via `compose.yaml` (SMTP 1025, UI 8025). Links are built from `APP_BASE_URL`. A plain-text "your password was changed" email is sent after a successful reset (FR-016).
- **Rationale**: Swapping the email adapter only takes configuration (Constitution II), and tests can assert on email content and links without a network.

## R11. Reset link handling on the page

- **Decision**: `GET /reset-password?token=…` only *checks* the token; it never consumes it. That matters because email link scanners prefetch links. The token is carried to the `POST` as a hidden form field. Response headers include `Referrer-Policy: no-referrer` and `Cache-Control: no-store`. Invalid, expired, used and unknown tokens all render the same "invalid or expired" page with a link to `/forgot-password` (FR-012).
- **Rationale**: This prevents accidental consumption and stops the token leaking through the Referer header or caches.

## R12. CSRF on the forms

- **Decision**: No CSRF token in this version.
- **Rationale**: There are no sessions or cookies-based authentication, so a cross-site form post can't act with a victim's authority. The reset form already requires knowing the secret token. This should be revisited if sessions or sign-in are added later.

## R13. Testing strategy (Constitution III, FR-017)

- **Decision**:
  - Unit tests for `PasswordResetService`, `PasswordPolicy`, `TokenGenerator` and the rate limiter, using a `MutableClock` and fakes.
  - `@SpringBootTest` + MockMvc tests for each of the 6 endpoints (2 API, 4 page), covering success, validation and every spec edge case. A concurrency test fires two parallel confirms and expects exactly one success.
  - A timing test (SC-004) compares median latency over N=50 requests for known vs. unknown emails and asserts a difference of at most 50 ms. It is tagged `timing` so it can run separately if CI is noisy.
- **Rationale**: Every acceptance scenario maps to at least one automated test (see quickstart.md).
