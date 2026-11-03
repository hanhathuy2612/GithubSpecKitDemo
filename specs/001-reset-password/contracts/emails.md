# Contract: Outgoing emails

Sent via the `EmailSender` port (research R10). Plain text, UTF-8. Sender address comes from `APP_MAIL_FROM`.

## Reset link email

- **To**: the account's email (ACTIVE accounts only).
- **Subject**: `Reset your password`
- **Body** must contain:
  - the link `${APP_BASE_URL}/reset-password?token=<raw token>`;
  - the statement that the link expires in 15 minutes and can be used once;
  - "If you did not request this, you can ignore this email."
- **Trigger**: a successful `requestReset` that issued a token. Sent asynchronously; a failure is logged as `password_reset.email_failed` and never surfaced to the requester.

## Password changed notification (FR-016)

- **To**: the account's email.
- **Subject**: `Your password was changed`
- **Body**: the time of the change (UTC) and a note to contact support if it wasn't the account owner. Contains **no** link or token.
- **Trigger**: a successful `resetPassword`.

**Required tests**: `FakeEmailSender` captures exactly one reset email per issued token. Its link token resolves to the stored hash. No email is sent for unknown, disabled or rate-limited requests. One notification is sent per successful reset.
