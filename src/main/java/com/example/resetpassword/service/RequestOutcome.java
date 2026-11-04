package com.example.resetpassword.service;

/**
 * Result of a reset request. Deliberately a single value: callers must not be able to tell known, unknown, disabled
 * or rate-limited requests apart (FR-005, FR-014).
 */
public enum RequestOutcome {
    ACCEPTED
}
