# Data Model: Forgot Password – Reset via Email

## Entities

### UserAccount (`user_account`)

| Field | Type | Rules |
|-------|------|-------|
| `id` | BIGINT, PK, identity | |
| `email` | VARCHAR(254), NOT NULL, UNIQUE | Stored normalized: trimmed and lower-cased (FR-003) |
| `password_hash` | VARCHAR(100), NOT NULL | BCrypt hash; never plain text (Constitution IV) |
| `status` | VARCHAR(16), NOT NULL | `ACTIVE` or `DISABLED`. Only `ACTIVE` accounts receive reset emails |
| `created_at` | TIMESTAMP WITH TIME ZONE, NOT NULL | |
| `updated_at` | TIMESTAMP WITH TIME ZONE, NOT NULL | Set when the password changes |

- Seeded by Flyway migration `V2__seed_accounts.sql`. Demo accounts (passwords documented in quickstart, stored as BCrypt hashes):
  - `alice@example.com`: ACTIVE
  - `bob@example.com`: ACTIVE
  - `disabled@example.com`: DISABLED
- No registration. Accounts are never created at runtime.

### PasswordResetToken (`password_reset_token`)

One issued reset link (the spec's "Password Reset Request").

| Field | Type | Rules |
|-------|------|-------|
| `id` | BIGINT, PK, identity | |
| `account_id` | BIGINT, NOT NULL, FK → `user_account.id` | Index `(account_id)` |
| `token_hash` | CHAR(64), NOT NULL, UNIQUE | Lowercase hex SHA-256 of the raw token. The raw token is never stored (FR-010) |
| `issued_at` | TIMESTAMP WITH TIME ZONE, NOT NULL | From the injected `Clock` |
| `expires_at` | TIMESTAMP WITH TIME ZONE, NOT NULL | `issued_at + 15 minutes` (FR-006) |
| `used_at` | TIMESTAMP WITH TIME ZONE, NULL | Set once on a successful reset (FR-007) |
| `invalidated_at` | TIMESTAMP WITH TIME ZONE, NULL | Set when a newer token is issued for the same account (FR-008) |

**Relationships**: UserAccount 1 ── 0..* PasswordResetToken.

## Derived state of a token

A token is **usable** only when all of the following hold (evaluated at `now` from `Clock`):

```
used_at IS NULL AND invalidated_at IS NULL AND now < expires_at
```

Validity is strict, so at exactly `issued_at + 15:00` the token is expired.

## State transitions

```
             issue (new token for account)
                     │
                     ▼
               ┌──────────┐  reset succeeds (atomic, with password update)  ┌──────┐
               │  USABLE  │ ───────────────────────────────────────────────▶ │ USED │
               └──────────┘                                                  └──────┘
                 │      │
 newer token     │      │ now ≥ expires_at
 issued          ▼      ▼
         ┌─────────────┐ ┌─────────┐
         │ INVALIDATED │ │ EXPIRED │   (derived, no write)
         └─────────────┘ └─────────┘
```

- USED, INVALIDATED and EXPIRED are terminal. Each is reported to the user identically as "invalid or expired" (FR-012).
- Only `PasswordResetService` performs these transitions (Constitution II).

## Validation rules

| Input | Rule | Source |
|-------|------|--------|
| email | Required; valid email format; ≤ 254 chars; normalized before use | FR-002, FR-003 |
| newPassword | 8–64 Unicode code points and ≤ 72 UTF-8 bytes; must not match the current password | FR-011, research R4 |
| confirmPassword | Must equal `newPassword` | FR-011 |
| token | Required; Base64URL, 43 characters. Malformed tokens are treated as unknown (same message) | FR-012 |

## Non-persisted state

- **Rate-limit windows**: In memory, keyed by `email:<normalized>` (3 per 15 min) and `ip:<addr>` (20 per 60 min). Not persisted and lost on restart (research R6).

## Migrations

- `V1__schema.sql`: both tables, unique constraints and indexes.
- `V2__seed_accounts.sql`: demo accounts.
