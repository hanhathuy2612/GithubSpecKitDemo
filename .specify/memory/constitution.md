<!--
Sync Impact Report
- Version change: (template) → 1.0.0
- Modified principles: none (initial ratification)
- Added principles:
  - I. Thin Controllers
  - II. Business Logic in Services
  - III. Automated Tests for Every Endpoint (NON-NEGOTIABLE)
  - IV. No Plain-Text Secrets or Reset Tokens (NON-NEGOTIABLE)
- Added sections: Security Constraints, Development Workflow, Governance
- Removed sections: none
- Templates requiring updates:
  - .specify/templates/plan-template.md ✅ compatible (Constitution Check gates are
    derived from this file at plan time — must cover principles I–IV)
  - .specify/templates/spec-template.md ✅ compatible (no changes needed)
  - .specify/templates/tasks-template.md ⚠ note: template marks tests as OPTIONAL;
    per Principle III, test tasks are REQUIRED for every endpoint — /speckit-tasks
    MUST always generate them (specs should state this, e.g. 001 FR-017)
- Follow-up TODOs: none
-->

# Reset Password Demo Constitution

## Core Principles

### I. Thin Controllers

Controllers (HTTP handlers, route functions) MUST only:

- receive and parse the incoming request,
- validate request shape (presence, type, format) and map it to a service call,
- map the service result or error to an HTTP response (status code, body, headers).

Controllers MUST NOT contain business rules, data-access calls, token generation,
email sending, or any branching that decides business outcomes.

**Rationale**: Keeping controllers thin makes the transport layer replaceable and
keeps behavior testable without HTTP.

### II. Business Logic in Services

All business logic MUST live in the service layer. Services own decisions such as
token issuance, expiry checks, single-use enforcement, and password updates.
Services MUST be callable and testable independently of the web framework.
Persistence and external integrations (database, email) MUST be accessed by
services through injectable dependencies so they can be replaced in tests.

**Rationale**: A single home for business rules avoids duplicated or diverging
logic and enables fast, isolated unit tests.

### III. Automated Tests for Every Endpoint (NON-NEGOTIABLE)

Every exposed endpoint MUST have automated tests covering at least:

- the success path,
- validation failures,
- each documented error/edge case in the feature spec.

Service-level business rules MUST have unit tests. A change that adds or modifies
an endpoint MUST NOT be merged without its tests passing in CI.

**Rationale**: Endpoints are the contract with clients; automated tests are the
only reliable proof that the contract holds.

### IV. No Plain-Text Secrets or Reset Tokens (NON-NEGOTIABLE)

Password reset tokens MUST NOT be stored in plain text. Only a one-way hash of the
token (e.g., SHA-256 of a high-entropy random token) MAY be persisted; the raw
token exists only in the link sent to the user. The same rule applies to
passwords (which MUST use a slow, salted password-hashing algorithm) and to any
other credential-equivalent secret. Raw tokens and passwords MUST NOT appear in
logs, error messages, or analytics.

**Rationale**: A database or log leak must not let an attacker reset accounts or
log in as users.

## Security Constraints

- Authentication-related responses MUST NOT reveal whether an account exists
  (no user enumeration through messages, status codes, or noticeable timing).
- Security-sensitive tokens MUST be generated with a cryptographically secure
  random source and MUST have an explicit expiry.
- Secrets and configuration (keys, SMTP credentials) MUST come from environment
  configuration, never from source control.

## Development Workflow

- Work follows Spec Kit flow: spec → plan → tasks → implement. Each feature lives
  in `specs/NNN-feature-name/`.
- Every implementation plan MUST include a Constitution Check confirming
  principles I–IV before design work proceeds, and again after design.
- Code review MUST verify: no business logic in controllers, tests exist for every
  new/changed endpoint, and no plain-text token/secret storage or logging.

## Governance

This constitution supersedes other project practices. Amendments require a
documented change to this file, a version bump, and an updated Sync Impact Report.

Versioning policy (semantic):

- MAJOR: removing or redefining a principle in a backward-incompatible way.
- MINOR: adding a principle or section, or materially expanding guidance.
- PATCH: clarifications and wording fixes with no semantic change.

All pull requests and reviews MUST verify compliance with this constitution.
Any justified deviation MUST be recorded in the plan's Complexity Tracking section.

**Version**: 1.0.0 | **Ratified**: 2026-11-03 | **Last Amended**: 2026-11-03
