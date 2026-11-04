package com.example.resetpassword.service.auth;

/** Result of a sign-in. Every failure cause is the single {@link #FAILED} value (FR-003). */
public sealed interface SignInOutcome {

    SignInOutcome FAILED = new Failed();

    record Success(IssuedCredentials credentials) implements SignInOutcome {
    }

    record Failed() implements SignInOutcome {
    }
}
