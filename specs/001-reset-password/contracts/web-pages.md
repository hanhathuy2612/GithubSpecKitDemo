# Contract: Server-rendered pages (Thymeleaf)

Handled by `PasswordResetPageController`. It calls the same `PasswordResetService` methods
as the API, so there is no duplicated logic (Constitution I–II). Forms are
`application/x-www-form-urlencoded`. All pages are minimal HTML with no JavaScript required.

| Method & path | Purpose | Service call | Result |
|---------------|---------|--------------|--------|
| `GET /forgot-password` | Show the email form | none | `200`, template `forgot-password` |
| `POST /forgot-password` | Submit email (`email`) | `requestReset` | Valid format → `200`, same template with the generic confirmation message (identical for known, unknown, disabled and rate-limited). Invalid format → `200`, same template with an inline field error |
| `GET /reset-password?token=…` | Open the link from the email | `checkToken` (read-only, never consumes the token) | Usable → `200`, template `reset-password` with the token in a hidden field. Otherwise → `200`, template `reset-invalid` with a link to `/forgot-password` |
| `POST /reset-password` | Submit `token`, `newPassword`, `confirmPassword` | `resetPassword` | Success → `200`, template `reset-success`. Password rule failed → `200`, `reset-password` with an inline error and the token kept. Token not usable → `200`, `reset-invalid` |

**Headers on `/reset-password` (GET and POST)**: `Referrer-Policy: no-referrer`, `Cache-Control: no-store` (research R11).

**Fixed user-facing messages**

- Request confirmation: "If an account exists for this email, a password reset link has been sent."
- Invalid link: "This link is invalid or has expired." plus "Request a new link".
- Success: "Your password has been reset."

**Required tests (MockMvc)**: each row's success and failure path. The generic message is identical for known and unknown emails. GET of a valid link does not consume it, so a following POST succeeds. Invalid, expired and used links render `reset-invalid`. Headers are present. Password rule errors keep the form usable.
