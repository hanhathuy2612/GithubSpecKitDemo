package com.example.resetpassword.service.auth;

import com.example.resetpassword.service.PasswordViolation;
import java.util.List;

/** Result of completing a registration from the emailed link. */
public sealed interface RegistrationOutcome {

    RegistrationOutcome INVALID = new Invalid();

    record Completed() implements RegistrationOutcome {
    }

    /** Password rules failed; the link stays usable. */
    record Rejected(List<PasswordViolation> violations) implements RegistrationOutcome {
    }

    record Invalid() implements RegistrationOutcome {
    }
}
