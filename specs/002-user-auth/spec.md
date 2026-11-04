# Feature Specification: User Authentication – Sign Up, Sign In, Sign Out, Change Password

**Feature Branch**: `002-user-auth` (work tracked on `main`)

**Created**: 2026-11-04

**Status**: Draft

**Input**: User description: "Auth service: đăng ký tài khoản, đăng nhập, đăng xuất và đổi mật khẩu khi đang đăng nhập. Người dùng đăng ký bằng email và mật khẩu; đăng nhập bằng email/mật khẩu nhận access token ngắn hạn và refresh token dài hạn (JWT access + refresh token); dùng refresh token để lấy access token mới; đăng xuất thu hồi refresh token; đổi mật khẩu yêu cầu mật khẩu hiện tại. Dùng chung kho tài khoản và quy tắc mật khẩu của tính năng 001-reset-password (8–64 ký tự, ≤72 byte UTF-8); sau khi đặt lại mật khẩu (001) hoặc đổi mật khẩu thì các phiên khác bị thu hồi. Không được lộ việc email đã tồn tại hay chưa."

## Clarifications

### Session 2026-11-04

- Q: Do sign-up, sign-in and change-password need HTML pages like 001, or only an API? → A: API only for 002; only the email-verification link opens a minimal server-rendered page (like the 001 reset page).
- Q: Are already-issued access credentials invalidated immediately after sign-out, password change or reset, or do they stay valid until they expire? → A: Password change or reset invalidates every access credential of the account immediately (via a per-account session version checked on each request); signing out one device lets its access credential expire naturally (≤ 15 minutes).
- Q: Is the 7-day refresh lifetime sliding (extended on each refresh) or absolute from sign-in? → A: Sliding 7 days of inactivity, capped at 30 days absolute from the original sign-in; after that the user must sign in again.
- Q: How should sign-in respond when an unverified account uses the correct password? → A: Same generic "Email or password is incorrect" response, but the system silently re-sends the verification link (counted against the registration email rate limit).
- Q: Should repeated failed sign-ins for one email lock out the real owner for 15 minutes? → A: No per-email lockout; use per-email exponential backoff between attempts (1, 2, 4… s, max 30 s) plus a cap of 20 failed sign-ins per IP per hour.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Sign in and stay signed in (Priority: P1) 🎯 MVP

A person with an account signs in with email and password. They receive a short-lived
**access credential**, used to call protected features, and a long-lived **refresh credential**,
used to get a new access credential when the old one expires, without typing the password again.

**Why this priority**: Every other story needs a signed-in user. The seeded accounts from
001 can already sign in, so this story is usable on its own.

**Independent Test**: Sign in as `alice@example.com`, call "who am I" with the access credential, wait until it expires, refresh it, and call "who am I" again.

**Acceptance Scenarios**:

1. **Given** an active account, **When** the user signs in with the correct email and password, **Then** they receive an access credential valid for 15 minutes and a refresh credential that expires after 7 days without use and never later than 30 days after this sign-in, and "who am I" returns their account.
2. **Given** a wrong password, an unknown email, a disabled account or an unverified account, **When** the user tries to sign in, **Then** they all get the same "Email or password is incorrect" response, and no credentials are issued.
3. **Given** an expired access credential, **When** it is used, **Then** the request is rejected as unauthenticated.
4. **Given** a valid refresh credential, **When** the user refreshes, **Then** they get a new access credential and a new refresh credential, and the old refresh credential stops working (rotation).
5. **Given** a refresh credential that was already rotated, **When** someone uses it again, **Then** it is rejected and every credential issued from that sign-in is revoked, because reuse signals theft.

---

### User Story 2 - Sign up with email verification (Priority: P1)

A new person registers with email and password. They receive an email with a verification link.
Once they open the link, the account becomes active and they can sign in.

**Why this priority**: Without it, the system only has seeded accounts. Verification is needed
both to prove ownership of the address and to keep "email already registered" from being
disclosed (FR-005).

**Independent Test**: Register `carol@example.com`, open the verification link from the email, and sign in.

**Acceptance Scenarios**:

1. **Given** an unused email, **When** the user registers with a password that meets the rules, **Then** they see "Check your email to finish creating your account", one verification email is sent, and the account cannot sign in until it is verified.
2. **Given** an email that already has an account, **When** someone registers with it, **Then** they see exactly the same message as in scenario 1. No second account is created, and the existing owner gets an email saying "someone tried to create an account with your email; if it was you, sign in or reset your password".
3. **Given** a verification link less than 24 hours old and not yet used, **When** it is opened, **Then** the account becomes active. A second use, an expired link and an unknown link all show "This link is invalid or has expired."
4. **Given** an unverified account, **When** its owner tries to sign in with the correct password, **Then** they get the generic "Email or password is incorrect" response and a fresh verification link is emailed; with a wrong password, nothing is sent.
5. **Given** a password that breaks the rules of 001 FR-011, or a confirmation that does not match, **When** the user registers, **Then** they see which rule failed and no email is sent.

---

### User Story 3 - Sign out (Priority: P2)

A signed-in user signs out on the current device.

**Why this priority**: Needed on shared devices. It is less urgent than getting in.

**Independent Test**: Sign in, sign out, then try to refresh using the refresh credential from that sign-in.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they sign out, **Then** the refresh credential of this sign-in is revoked and can no longer be used. Other devices stay signed in.
2. **Given** a refresh credential that is already revoked or unknown, **When** someone signs out with it, **Then** the request still succeeds (signing out is idempotent) and reveals nothing.

---

### User Story 4 - Change password while signed in (Priority: P2)

A signed-in user changes their password by entering the current password and a new one.

**Why this priority**: Important for account hygiene, but it builds on sign-in.

**Independent Test**: Sign in on two devices, change the password on device A, then check that device B can no longer refresh and that the new password works for sign-in.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they enter the correct current password and a valid new password (001 FR-011 rules, different from the current one), **Then** the password changes, every **other** sign-in of the account is revoked, all previously issued access credentials stop working immediately, the current device receives a fresh credential pair and stays signed in, and a "Your password was changed" email is sent.
2. **Given** a wrong current password, **When** they try to change it, **Then** the password is not changed and they see "Current password is incorrect".
3. **Given** a new password that breaks a rule, **When** they submit it, **Then** they see which rule failed and nothing changes.

---

### User Story 5 - Password reset signs out everywhere (Priority: P2)

When a password is reset through feature 001, every existing sign-in of that account is revoked.
This restores the session sign-out that 001 FR-013 deferred.

**Independent Test**: Sign in, complete a 001 password reset for the same account, then try to refresh. The refresh must be rejected.

**Acceptance Scenarios**:

1. **Given** an account signed in on any number of devices, **When** its password is reset via 001, **Then** all of its refresh credentials are revoked.

---

### Edge Cases

- After signing out one device, that device's already-issued access credential stays valid until it expires (at most 15 minutes); its refresh credential stops working immediately. After a password change or reset, **every** access credential of the account issued before the change is rejected immediately — including the current device's, which receives a fresh pair in the change-password response (FR-015a).
- Two refreshes with the same refresh credential at the same moment: at most one succeeds. The other is treated as reuse (Story 1, scenario 5).
- Registering the same new email twice before verifying: only the latest verification link works, and earlier links are invalidated.
- An unverified registration that is never completed may be registered again with a new password. The latest registration wins.
- Repeated failed sign-ins: no per-email lockout. After N consecutive failures for an email, the next attempt for that email is not evaluated until 2^(N-1) seconds have passed (1, 2, 4, 8, 16, then capped at 30 s); attempts inside that wait get the same generic failure without checking the password. A successful sign-in resets the counter. Unknown emails follow the same backoff, so behavior is identical. Separately, at most 20 failed sign-ins per IP per hour; over that, every sign-in from the IP gets the generic failure.
- Repeated registrations: the same limits as 001 apply (3 per email per 15 minutes, 20 per IP per hour), with the same "check your email" response.
- A disabled account is treated like a wrong password at sign-in, and its refresh credentials stop working.
- Refreshing on day 29 of a session yields a refresh credential that still expires at the 30-day cap, not 7 days later; on or after the cap the refresh is rejected and the user must sign in again.
- A tampered, malformed or wrongly signed access credential is rejected as unauthenticated, the same as an expired one.

## Requirements *(mandatory)*

### Functional Requirements

**Sign in and credentials**

- **FR-001**: Users MUST be able to sign in with email and password. Email is normalized as in 001 FR-003.
- **FR-002**: A successful sign-in MUST issue an access credential valid for 15 minutes and a refresh credential. Each refresh credential expires 7 days after it was issued (idle timeout), and no refresh credential of a sign-in may be valid later than 30 days after that sign-in (absolute cap); refreshing never extends past the cap.
- **FR-003**: Every sign-in failure (unknown email, wrong password, disabled or unverified account, rate-limited) MUST produce one identical response, and the response time MUST NOT reveal which case occurred (SC-003).
- **FR-003a**: When an UNVERIFIED account signs in with the correct password, the system MUST return the same generic failure as FR-003 and MUST send a new verification link (invalidating older ones), subject to the FR-012 rate limit. No verification email is sent for a wrong password.
- **FR-003b**: Sign-in throttling MUST NOT lock an account out for a fixed period. Per normalized email (known or unknown alike), consecutive failures impose an exponential wait before the next evaluated attempt (1 s doubling up to 30 s), reset by a success; attempts during the wait return the FR-003 response without checking the password. Per client IP, at most 20 failed sign-ins per rolling hour.
- **FR-004**: A protected "who am I" capability MUST return the signed-in account (id and email) for a valid access credential, and reject missing, expired, malformed or tampered credentials identically.

**Refresh and revocation**

- **FR-005**: Refreshing MUST rotate credentials: it issues a new access and a new refresh credential, and invalidates the presented refresh credential.
- **FR-006**: Presenting a refresh credential that was already rotated MUST revoke every refresh credential descended from the same sign-in.
- **FR-007**: Refresh credentials MUST be revocable server-side and MUST NOT be stored in a form that would let someone holding the stored data use them (same principle as Constitution IV).
- **FR-008**: Refresh credentials of disabled accounts MUST be rejected.

**Sign up**

- **FR-009**: Users MUST be able to register with an email, a password and a password confirmation. Password rules are those of 001 FR-011.
- **FR-010**: The registration response MUST be identical whether or not the email already has an account. An existing owner receives an "attempted registration" notice instead of a verification link.
- **FR-011**: A new account MUST stay unverified and unable to sign in until its verification link is used. The link is single-use, valid for 24 hours, and stored only as a one-way hash. A newer registration for the same unverified email invalidates older links and replaces the pending password.
- **FR-011a**: The verification link MUST open a minimal server-rendered page that shows either "Your email is verified. You can now sign in." or "This link is invalid or has expired." All other capabilities of this feature are API-only.
- **FR-012**: Registration requests MUST be rate limited exactly as 001 FR-014.

**Sign out**

- **FR-013**: Signing out MUST revoke the refresh credential of the current sign-in only, and MUST succeed even for an unknown or already revoked credential.

**Change password**

- **FR-014**: A signed-in user MUST be able to change their password by providing the current password and a new password plus confirmation that satisfy 001 FR-011 (8–64 characters, ≤ 72 UTF-8 bytes, different from the current password).
- **FR-015**: A successful change MUST revoke every other sign-in of the account, keep the current one, and send the "Your password was changed" email.
- **FR-015a**: Each account MUST carry a session version that is embedded in every access credential and checked on every protected request. A password change or a 001 password reset MUST increment it, so all access credentials issued before the change are rejected immediately. The change-password response MUST return a new access and refresh credential pair for the current device.
- **FR-016**: Failed current-password checks MUST be rate limited (5 per account per 15 minutes) so a stolen access credential cannot be used to brute-force the password.

**Cross-feature**

- **FR-017**: Completing a 001 password reset MUST revoke all refresh credentials of the account and increment its session version (FR-015a).
- **FR-018**: Raw credentials, verification tokens and passwords MUST NOT appear in logs, error messages or stored data (001 FR-015, Constitution IV).
- **FR-019**: Every endpoint introduced by this feature MUST have automated tests covering success, validation failures and the edge cases above (Constitution III).

### Key Entities

- **User Account** (from 001): gains a status `UNVERIFIED` in addition to `ACTIVE` and `DISABLED`, and a session version (FR-015a). Seeded accounts are `ACTIVE`.
- **Email Verification**: one issued verification link for an unverified account. It holds a token hash, an expiry (24 hours) and a used or invalidated marker.
- **Sign-in Session (refresh credential family)**: one successful sign-in on one device. It holds the account, the sign-in time (for the 30-day cap), the current refresh credential (hashed) with its 7-day expiry, revocation state, and the chain of rotated credentials used to detect reuse.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new user can go from registration to being signed in, including email verification, in under 3 minutes.
- **SC-002**: Registration responses for new and existing emails, and sign-in failures for every cause, are identical in message, status and structure in 100% of test cases.
- **SC-003**: Median response time of sign-in with an unknown email vs. a wrong password, and of registration with a new vs. an existing email, differs by no more than 50 ms.
- **SC-004**: 100% of revoked, rotated, expired or tampered credentials are rejected in automated tests. After a password change or reset, no other device can refresh, and every access credential issued before the change is rejected on its next use.
- **SC-005**: Zero raw refresh credentials, verification tokens or passwords are found in stored data or logs during security review.

## Assumptions

- **Credential format**: the project owner chose self-contained signed access tokens (JWT) and refresh tokens. The plan picks the signing algorithm, the key handling and the server-side refresh store.
- Access credentials are not revoked one by one: sign-out of a single device relies on the 15-minute lifetime, while password change/reset invalidates all of an account's access credentials at once through a per-account session version (FR-015a).
- There is no limit on how many devices an account can be signed in on at once.
- Signing out of all devices is not a separate action in this version. It happens through password change or reset.
- There are no roles or permissions beyond "signed in": every active account has the same access.
- The existing email infrastructure from 001 is reused. Sign-up, sign-in, refresh, sign-out, change-password and "who am I" are API-only; web pages for them are out of scope. The only new page is the minimal email-verification page opened from the link.
- Lifetimes are fixed for this version: access 15 minutes, refresh 7 days idle / 30 days absolute, verification link 24 hours. Rate limits follow the defaults in Edge Cases.
