package com.example.resetpassword.service.auth;

import com.example.resetpassword.service.PasswordViolation;
import java.util.List;

public sealed interface PasswordChangeOutcome {

    PasswordChangeOutcome WRONG_CURRENT_PASSWORD = new WrongCurrentPassword();
    PasswordChangeOutcome TOO_MANY_ATTEMPTS = new TooManyAttempts();
    PasswordChangeOutcome INVALID_SESSION = new InvalidSession();

    /** Fresh pair for the current device; every older access token is now stale (FR-015a). */
    record Changed(IssuedCredentials credentials) implements PasswordChangeOutcome {
    }

    record Rejected(List<PasswordViolation> violations) implements PasswordChangeOutcome {
    }

    record WrongCurrentPassword() implements PasswordChangeOutcome {
    }

    record TooManyAttempts() implements PasswordChangeOutcome {
    }

    /** The current session was revoked meanwhile (for example, signed out). */
    record InvalidSession() implements PasswordChangeOutcome {
    }
}
