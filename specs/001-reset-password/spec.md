# Feature Specification: Forgot Password – Reset via Email

**Feature Branch**: `001-reset-password`
**Created**: 2026-11-03
**Status**: Draft
**Input**: User description: "Quên mật khẩu, đặt lại qua email. Người dùng nhập email và nhận link đặt lại; link hết hạn sau 15 phút và chỉ dùng được một lần; nếu email không tồn tại thì vẫn trả về cùng thông báo để tránh dò tìm tài khoản."

## Clarifications

### Session 2026-11-03

- Q: Is the scope backend API endpoints only, or also the user-facing web pages? → A: API plus minimal server-rendered web UI (two HTML pages: "Forgot password" and "Set new password").
- Q: What rules must the new password satisfy? → A: 8–64 characters, no character-class requirements, and it must differ from the current password.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Request a password reset link (Priority: P1)

A user who has forgotten their password opens the "Forgot password" page, enters
their email address and submits. The system shows a confirmation message and, if
an account with that email exists, sends an email containing a password reset link.

**Why this priority**: Without the ability to request a link, no recovery is
possible. This is the entry point of the whole feature.

**Independent Test**: Submit a registered email and verify that a generic
confirmation is shown and exactly one reset email is delivered to that address.

**Acceptance Scenarios**:

1. **Given** a registered account with email `a@example.com`, **When** the user submits `a@example.com` on the Forgot password form, **Then** the system shows the generic confirmation message and sends one reset email containing a reset link to `a@example.com`.
2. **Given** the user enters a value that is not a valid email format, **When** they submit, **Then** the system shows a validation error and sends no email.
3. **Given** a registered account, **When** the user submits the email with different letter case or surrounding spaces (e.g. ` A@Example.com `), **Then** the system treats it as the same account.

---

### User Story 2 - Set a new password using the link (Priority: P1)

The user opens the reset link from the email within 15 minutes, enters a new
password (and its confirmation), and submits. The password is changed and the user
can sign in with the new password.

**Why this priority**: This completes the recovery; together with Story 1 it forms
the minimum viable feature.

**Independent Test**: Issue a link for a known account, open it within 15 minutes,
set a new password, then confirm sign-in works with the new password and fails
with the old one.

**Acceptance Scenarios**:

1. **Given** a reset link issued less than 15 minutes ago and never used, **When** the user submits a valid new password, **Then** the password is updated, the user sees a success message, and they can sign in with the new password.
2. **Given** a valid link, **When** the user submits a new password shorter than 8 or longer than 64 characters, identical to the current password, or with a confirmation that does not match, **Then** the system shows a validation error, the password is not changed, and the link remains usable until it expires.
3. **Given** a password was successfully reset, **When** the user tries to sign in with the old password, **Then** sign-in fails.

---

### User Story 3 - Prevent account enumeration (Priority: P1)

Someone submits an email that does not belong to any account. The system responds
exactly as it does for a registered email, so nobody can learn which emails have
accounts.

**Why this priority**: This is a security requirement of the feature, not an
enhancement; shipping Story 1 without it would leak account existence.

**Independent Test**: Submit one registered and one unregistered email and compare
responses: message, status, and response shape must be identical; no email is sent
for the unregistered address.

**Acceptance Scenarios**:

1. **Given** no account exists for `nobody@example.com`, **When** it is submitted, **Then** the system shows the same confirmation message, with the same response status and structure, as for a registered email, and no email is sent.
2. **Given** a registered and an unregistered email, **When** each is submitted, **Then** response times are not noticeably different (see SC-004).

---

### User Story 4 - Reject expired or already-used links (Priority: P2)

A user opens a reset link that is older than 15 minutes, or that has already been
used. The system refuses it and offers to request a new link.

**Why this priority**: Required for security, but it guards the main flow rather
than delivering new user value, so it follows Stories 1–3.

**Independent Test**: Use a link once successfully, then reuse it; separately, open
a link after 15 minutes. Both must be rejected without changing the password.

**Acceptance Scenarios**:

1. **Given** a link issued more than 15 minutes ago, **When** the user opens it or submits a new password with it, **Then** the system shows "This link is invalid or has expired" with an option to request a new link, and the password is not changed.
2. **Given** a link that was already used to reset the password, **When** it is opened or submitted again, **Then** it is rejected the same way.
3. **Given** a malformed or unknown link, **When** it is opened, **Then** it is rejected with the same message as an expired link.

---

### Edge Cases

- User requests several links in a row: only the most recently issued link is valid; issuing a new link invalidates any earlier unused links for that account.
- User submits the new-password form twice concurrently with the same link: at most one submission succeeds; the other is rejected as already used.
- Link opened at exactly the 15-minute boundary: a link is valid only while less than 15 minutes have elapsed since issuance.
- Email delivery fails: the user still sees the generic confirmation (no disclosure); the failure is recorded for operators.
- Excessive requests for the same email or from the same source: requests are rate limited, and the response to the requester remains the generic confirmation.
- After a successful reset, other active sessions of that account are signed out.
- Account is disabled/locked: treated like a non-existent account (generic message, no email).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a minimal server-rendered "Forgot password" page with a form where a user enters an email address, backed by a reset-request endpoint that can also be called directly.
- **FR-001a**: System MUST provide a minimal server-rendered "Set new password" page, opened from the reset link, backed by a password-reset endpoint that can also be called directly. Both pages MUST show validation errors and result messages inline. A separate frontend application is out of scope.
- **FR-002**: System MUST validate the email format and reject invalid input with a validation error before any lookup.
- **FR-003**: System MUST normalize the email (trim spaces, case-insensitive match) before looking up the account.
- **FR-004**: When the email belongs to an active account, the system MUST send that address one email containing a unique password reset link.
- **FR-005**: The response to a reset request MUST be identical (message text, status, response structure) whether or not the email belongs to an account; no email is sent when there is no matching active account.
- **FR-006**: A reset link MUST expire 15 minutes after it is issued.
- **FR-007**: A reset link MUST be usable for only one successful password reset; after use it MUST be rejected.
- **FR-008**: Issuing a new reset link for an account MUST invalidate all previously issued unused links for that account.
- **FR-009**: The reset link MUST contain an unguessable token generated from a cryptographically secure random source.
- **FR-010**: The system MUST NOT store the reset token in plain text; only a one-way hash of the token may be persisted (Constitution IV).
- **FR-011**: Opening a valid link MUST let the user enter and confirm a new password. The new password MUST be 8–64 characters long (no character-class requirements), MUST match its confirmation, and MUST differ from the current password; otherwise the system shows a validation error stating which rule failed.
- **FR-012**: Invalid, expired, used, or unknown links MUST all produce the same "invalid or expired" message and offer a way to request a new link.
- **FR-013**: On successful reset, the system MUST update the password, mark the link as used, and sign out the account's other active sessions.
- **FR-014**: The system MUST rate-limit reset requests per email and per source, without changing the response shown to the requester.
- **FR-015**: Raw reset tokens and passwords MUST NOT appear in logs, error messages, or analytics.
- **FR-016**: The system SHOULD notify the account's email after a successful password change, so the owner can react if it was not them.
- **FR-017**: Every endpoint introduced by this feature MUST have automated tests covering success, validation failures, and the edge cases above (Constitution III).

### Key Entities

- **User Account**: Existing account identified by a unique email; has a password credential and an active/disabled status.
- **Password Reset Request**: One issued reset link for an account. Attributes: owning account, token hash (never the raw token), issued-at time, expires-at time (issued + 15 min), used-at time (empty until used), invalidated flag.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user with access to their mailbox can go from "Forgot password" to signing in with a new password in under 3 minutes.
- **SC-002**: 100% of reset links are rejected once 15 minutes have passed since issuance, and 100% are rejected on a second use (verified by automated tests).
- **SC-003**: Responses for registered and unregistered emails are indistinguishable in message, status, and structure in 100% of test cases.
- **SC-004**: Median response time for registered vs. unregistered emails differs by no more than 50 ms under normal load.
- **SC-005**: Reset emails reach the user's inbox within 1 minute in 95% of requests.
- **SC-006**: Zero raw reset tokens are found in stored data or logs during security review.

## Assumptions

- Users already have accounts identified by a unique email; registration and sign-in exist and are out of scope.
- An email sending capability is available to the system.
- Password policy for this feature is defined in FR-011 (8–64 characters, must differ from current password); no breached-password list check in this version.
- Rate-limit thresholds (e.g., max 5 requests per email per hour) will be confirmed during planning; the exact numbers do not change the behavior described here.
- Sign-out of other sessions after reset is the expected default for this product.
- Only one language for the messages is required initially; localization is out of scope.
