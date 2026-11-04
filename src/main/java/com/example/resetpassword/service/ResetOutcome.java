package com.example.resetpassword.service;

import java.util.List;

/** Result of {@link PasswordResetService#resetPassword}. */
public sealed interface ResetOutcome {

    /** Every token failure (expired, used, invalidated, unknown, malformed) maps to this one value (FR-012). */
    ResetOutcome INVALID_OR_EXPIRED = new InvalidOrExpired();

    record Success() implements ResetOutcome {
    }

    /** Password rules failed; the token was not consumed. */
    record Rejected(List<PasswordViolation> violations) implements ResetOutcome {
    }

    record InvalidOrExpired() implements ResetOutcome {
    }
}
