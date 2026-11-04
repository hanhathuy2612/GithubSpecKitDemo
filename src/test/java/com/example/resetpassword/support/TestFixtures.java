package com.example.resetpassword.support;

import java.time.Instant;

public final class TestFixtures {

    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    public static final String SEED_PASSWORD = "demo-password-1";
    /** BCrypt(12) hash of {@link #SEED_PASSWORD}, identical to V2__seed_accounts.sql. */
    public static final String SEED_HASH = "$2a$12$fX3fkCn/gWvNY5KDlAakG.mgZuNNd7J7SNk1jZHI6sMVVi5No6YHK";
    public static final String ALICE = "alice@example.com";
    public static final String BOB = "bob@example.com";
    public static final String DISABLED = "disabled@example.com";
    public static final String NOBODY = "nobody@example.com";
    public static final String GENERIC_MESSAGE =
            "If an account exists for this email, a password reset link has been sent.";
    public static final String INVALID_LINK_MESSAGE = "This link is invalid or has expired.";

    private TestFixtures() {
    }
}
