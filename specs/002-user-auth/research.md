# Research: User Authentication (002)

Decisions build on 001 (`specs/001-reset-password/research.md`). Format: Decision / Rationale / Alternatives.

## R1. JWT validation: Spring Security resource server

- **Decision**: Add Spring Security with OAuth2 resource-server JWT support. One `SecurityFilterChain`:
  - stateless (no HTTP session);
  - CSRF disabled (bearer tokens are not sent automatically by browsers; 001 R12 already decided against CSRF tokens for the unauthenticated forms);
  - `permitAll` for every 001 route, `/error` (so 404/405/415 error dispatches keep their status instead of becoming 401), and for `register`, `sign-in`, `refresh`, `sign-out` and `/complete-registration`;
  - `authenticated` for `/api/auth/me` and `/api/auth/password`.
- **Rationale**: Signature, `exp`, `iss` and algorithm checks are security-critical, and the framework implementation is well tested. Writing our own filter would be more code to get wrong.
- **Validators**: build the decoder's validator explicitly as `DelegatingOAuth2TokenValidator(issuer, JwtTimestampValidator(zero skew) with the injected Clock, SessionVersionValidator)`. Do **not** use `JwtValidators.createDefault…`, whose timestamp check reads the system clock and would reject every token minted with the test `MutableClock`.
- **Watch**: Spring Security adds default response headers. 001's `Cache-Control: no-store` on `/reset-password` must still win; the existing `ResetPasswordPageTest` covers this.
- **Alternatives**: Using the Nimbus JOSE library directly with a custom filter (less dependency weight, more security-sensitive code). An opaque access token with a DB lookup per request (gives up the owner's JWT choice).

## R2. Signing algorithm and key

- **Decision**: ES256 (ECDSA P-256) with a `kid` header. The private key comes from `APP_JWT_PRIVATE_KEY` (PKCS#8 PEM) and the key id from `APP_JWT_KEY_ID`. If the key is absent and the `prod` profile is not active, a key pair is generated at startup with a warning; with `prod`, startup fails.
- **Rationale**: An asymmetric key lets other services verify tokens with only the public key, and is never shared. ES256 keys and signatures are small. A generated dev key only affects access tokens (≤ 15 min) on restart, because refresh tokens are opaque (R3).
- **Alternatives**: HS256 (a shared secret, so any verifier could also mint tokens). RS256 (larger keys and signatures, otherwise equivalent). EdDSA (needs an extra dependency for Nimbus).
- **Out of scope**: key rotation with multiple active keys. The `kid` is included so it can be added later.

## R3. Refresh tokens are opaque, not JWT

- **Decision**: 32 random bytes, Base64URL (43 characters), the same as 001 `TokenGenerator`. Only `SHA-256` is stored in `refresh_token.token_hash`.
- **Rationale**: Refresh tokens must be revocable and rotate server-side anyway (FR-005–FR-008), so a self-contained format adds nothing. Hashed storage meets FR-007 and Constitution IV. The spec's "JWT access + refresh token" is satisfied: the access token is a JWT, and the refresh token is a server-tracked bearer token.
- **Alternatives**: A JWT refresh token with a `jti` denylist (still needs DB state, and the token is larger).

## R4. Rotation, reuse detection and lifetimes

- **Decision**: Each sign-in creates an `auth_session` with `absolute_expires_at = now + 30 d`. Each refresh token gets `expires_at = min(now + 7 d, absolute_expires_at)`. On refresh, in one transaction:
  1. Look up the token by hash. If it is unknown → invalid.
  2. If the session is revoked, or `now >= expires_at` → invalid.
  3. If `rotated_at` is already set → **reuse**: revoke the session with `REUSE`, then return invalid.
  4. Otherwise run the conditional `UPDATE … SET rotated_at = now WHERE id = ? AND rotated_at IS NULL`. If 0 rows were updated, treat it as reuse (a concurrent double refresh, per the spec's edge case). If 1 row, insert the successor and issue a new access token.
- **Rationale**: The same race-safe conditional update pattern as 001 R8. Revoking on reuse is the usual defense against stolen refresh tokens.
- **Disabled accounts**: the account status is checked on refresh (FR-008).

## R5. Session version for immediate invalidation (FR-015a)

- **Decision**: `user_account.session_version` is embedded as the `sv` claim. A `SessionVersionValidator` (an `OAuth2TokenValidator<Jwt>`) loads the account by `sub` (a primary-key read) and rejects the token if the account is not `ACTIVE` or `sv` differs. Password change and 001 reset run `UPDATE … SET session_version = session_version + 1` in the same transaction that changes the password.
- **Rationale**: One cheap indexed read per protected request, and immediate invalidation exactly where the spec requires it. Sign-out of one device does not bump the version; that is clarification 2.

## R6. No enumeration and equal timing (FR-003, FR-010, SC-003)

- **Decision**:
  - **Sign-in**: always run one BCrypt `matches`. For unknown emails, compare against a fixed dummy hash of the same strength (12), computed once at startup.
  - **Registration**: always run one BCrypt `encode` of the submitted password, before branching on whether the account exists.
  - **Email**: all mail is sent asynchronously after commit (001 R5).
  - **Responses**: one fixed response per endpoint.
- **Rationale**: BCrypt (~250 ms) dominates the latency. Making every path do exactly one BCrypt operation keeps the medians within 50 ms.
- **Registration branches** (all return the same `202`; none hashes a password):

  | Email state | Action |
  |-------------|--------|
  | no account | invalidate unused `registration_token` rows for the email, insert a new one, send the registration link |
  | `ACTIVE` | send the "attempted registration" notice |
  | `DISABLED` | send nothing |

  Every branch does one account lookup and generates and hashes one token (as in 001 R5), so CPU work is symmetric. Only the "no account" branch writes a row, which is a millisecond-scale difference.

## R7. Throttling (FR-003b, FR-012, FR-016)

- **Decision**:
  - **`SignInThrottle`** (in memory, injected `Clock`):
    - Per email: a consecutive-failure count `n` and `nextAllowedAt = lastFailure + min(2^(n-1), 30) s`. While `now < nextAllowedAt`, the attempt returns the generic failure without checking the password. A success clears the entry. Unknown emails are tracked the same way.
    - Per IP: a sliding window of failures (20 per hour), recording failures only.
  - **Registration**: the existing `RateLimiter` with 001's limits (3 per email per 15 min, 20 per IP per hour).
  - **Change password**: failed current-password checks are counted per account (5 per 15 min). Over the limit, the endpoint returns `429` without checking the password.
- **Rationale**: The backoff stops brute force without letting an attacker lock out the real owner (clarification 5). The 429 for change password is not an enumeration risk, because the caller is already authenticated.
- **Known limitation**: per instance, and reset on restart (as in 001 R6).

## R8. Registration link: choose the password after proving the mailbox

- **Decision**: `POST /api/auth/register` takes only the email. The link opens `GET /complete-registration?token=`, which **checks** the token (read-only) and renders a choose-password form with the token in a hidden field. `POST /complete-registration` runs 001 `PasswordPolicy` (no "same as current"), then, in one transaction: a conditional `markUsedIfUsable` on the token, plus an insert of an ACTIVE `user_account` with the BCrypt hash. If the insert hits the unique email constraint (a racing completion, or the email was registered meanwhile), the transaction rolls back and the result is "invalid". Both pages send `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.
- **Rationale**: This fixes `/speckit-analyze` finding H1. Whoever sets the password has proven they can read the mailbox, so a victim clicking a link someone else requested only lets the victim choose their own password. GET never consumes the link, so mail scanners cannot burn it (same as 001 R11). There is no UNVERIFIED account state, which removes the "replace pending password" and "unverified sign-in" cases.
- **Alternatives**: password at registration plus activate-on-open (vulnerable to pre-registration takeover); re-entering the registration password on the verify page (works, but is confusing for victims and keeps unverified accounts).

## R9. Change password keeps the current session

- **Decision**: The `sid` claim identifies the current session. In one transaction:
  1. Verify the current password (subject to the R7 limit).
  2. Validate the new password with 001 `PasswordPolicy`.
  3. Update the hash and bump `session_version`.
  4. Revoke every other session (`PASSWORD_CHANGED`).
  5. Rotate the current session's refresh token and issue a new access token with the new `sv`.

  Then send the "Your password was changed" email after commit.
- **Rationale**: This meets FR-015 and FR-015a: the old access token stops working, and the caller gets a fresh pair.

## R10. Logging and secrecy

- **Decision**: New events: `auth.registered` (outcome `link_sent`/`existing_notified`/`ignored`/`rate_limited`), `auth.registration_completed`, `auth.sign_in` (`success`/`failure` + reason), `auth.refresh` (`success`/`invalid`/`reuse_revoked`), `auth.sign_out`, `auth.password_changed`, `auth.sessions_revoked` (reason, count). They carry account and session ids only. JWTs, refresh tokens, registration tokens, passwords and emails are never logged.
- **Tests**: `AuthSecrecyTest` extends 001's log and storage secrecy checks to the new flows and tables.

## R11. Testing approach

- **Decision**:
  - Reuse `IntegrationTestBase`, extended to clear the new tables and non-seeded accounts in FK-safe order (`refresh_token` → `auth_session` → `registration_token` → `password_reset_token` → non-seeded `user_account`), then reset seeded accounts (hash, status, `session_version`) and `SignInThrottle`.
  - JWT keys are generated per test context.
  - `MutableClock` drives every expiry: 15 min, 7 d idle, 30 d cap, 24 h registration link, and backoff.
  - A concurrency test sends two parallel refreshes with the same token and expects exactly one 200, after which the session is revoked.
  - The SC-003 timing test is tagged `timing` and runs in the existing CI job.
