package com.example.resetpassword.service;

import java.util.Locale;

/** FR-003: trim surrounding whitespace and compare case-insensitively. */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
    }
}
