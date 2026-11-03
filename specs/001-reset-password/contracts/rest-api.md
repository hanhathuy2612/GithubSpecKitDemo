# Contract: REST API

Base path: `/api/password-reset`. JSON in, JSON out (`application/json`). Errors use
RFC 9457 Problem Details (`application/problem+json`).

Each endpoint is handled by `PasswordResetApiController`, which only maps the request to a
service call and the result to a response (Constitution I).

---

## POST `/api/password-reset/requests`

Request a reset link. Service: `PasswordResetService.requestReset(email, clientIp)`.

**Request**

```json
{ "email": "alice@example.com" }
```

**Responses**

| Status | When | Body |
|--------|------|------|
| `202 Accepted` | Valid email format: known, unknown, disabled, or rate-limited (identical in all cases, FR-005/FR-014) | `{ "message": "If an account exists for this email, a password reset link has been sent." }` |
| `400 Bad Request` | Missing or invalid email format (FR-002) | Problem: `type: /problems/validation`, `errors: [{ "field": "email", "message": "Must be a valid email address." }]` |

**Required tests**: known email → 202 and 1 email captured. Unknown and disabled → byte-identical 202 body and no email. Invalid format → 400 and no email. Mixed-case or padded email → treated as the same account. 4th request within 15 min → 202, no email. 21st request from one IP within 1 h → 202, no email. A second request invalidates the first link. Timing (SC-004).

---

## POST `/api/password-reset/confirm`

Set a new password. Service: `PasswordResetService.resetPassword(token, newPassword, confirmPassword)`.

**Request**

```json
{ "token": "<43-char base64url>", "newPassword": "correct horse", "confirmPassword": "correct horse" }
```

**Responses**

| Status | When | Body |
|--------|------|------|
| `200 OK` | Token usable and password valid. Password updated and token marked used atomically (FR-013) | `{ "message": "Your password has been reset." }` |
| `400 Bad Request` | Password rule failed (FR-011). The token is **not** consumed | Problem: `type: /problems/validation`, `errors: [{ "field": "newPassword" \| "confirmPassword", "code": "TOO_SHORT" \| "TOO_LONG" \| "SAME_AS_CURRENT" \| "MISMATCH", "message": "..." }]` |
| `400 Bad Request` | Token invalid, expired, used, invalidated, unknown or malformed (FR-012). One response for all cases | Problem: `type: /problems/invalid-reset-token`, `title: "This link is invalid or has expired."` |

Token checks run before password-rule checks, so password feedback is shown only for usable tokens.

**Required tests**: success → 200, credential check accepts the new password and rejects the old. Each password rule → 400 with the matching code and the token stays usable. Expired at exactly 15:00 → invalid. Reused → invalid. Invalidated by a newer link → invalid. Malformed or unknown → invalid with the identical body. Two concurrent confirms → exactly one 200. "Password changed" notification email captured (FR-016).

---

## Logging contract (all endpoints)

Request bodies, tokens, passwords and emails are never logged (FR-015). See research R9 for the event names.
