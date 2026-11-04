package com.example.resetpassword.web;

/** Fixed user-facing messages (contracts/web-pages.md, contracts/rest-api.md). */
public final class ResetMessages {

    public static final String REQUEST_ACCEPTED =
            "If an account exists for this email, a password reset link has been sent.";
    public static final String RESET_DONE = "Your password has been reset.";
    public static final String INVALID_LINK = "This link is invalid or has expired.";
    public static final String INVALID_EMAIL = "Must be a valid email address.";

    public static String message(com.example.resetpassword.service.PasswordViolation violation) {
        return switch (violation) {
            case TOO_SHORT -> "Password must be at least 8 characters.";
            case TOO_LONG -> "Password must be at most 64 characters (72 bytes).";
            case SAME_AS_CURRENT -> "New password must be different from the current password.";
            case MISMATCH -> "Passwords do not match.";
        };
    }

    private ResetMessages() {
    }
}
