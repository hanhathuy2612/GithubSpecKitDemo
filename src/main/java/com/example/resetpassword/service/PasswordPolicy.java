package com.example.resetpassword.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * FR-011: 8–64 Unicode code points and at most 72 UTF-8 bytes (BCrypt input limit, research R4), no character-class
 * rules, must equal its confirmation, must differ from the current password.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 8;
    public static final int MAX_CODE_POINTS = 64;
    public static final int MAX_UTF8_BYTES = 72;

    private final PasswordEncoder passwordEncoder;

    public PasswordPolicy(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public List<PasswordViolation> check(String newPassword, String confirmPassword, String currentPasswordHash) {
        List<PasswordViolation> violations = new ArrayList<>();
        String candidate = newPassword == null ? "" : newPassword;
        int codePoints = candidate.codePointCount(0, candidate.length());
        boolean lengthOk = true;
        if (codePoints < MIN_CODE_POINTS) {
            violations.add(PasswordViolation.TOO_SHORT);
            lengthOk = false;
        } else if (!fitsMaxLength(candidate)) {
            violations.add(PasswordViolation.TOO_LONG);
            lengthOk = false;
        }
        if (!candidate.equals(confirmPassword == null ? "" : confirmPassword)) {
            violations.add(PasswordViolation.MISMATCH);
        }
        // Only hash-compare passwords the encoder accepts (BCrypt rejects inputs over 72 bytes).
        if (lengthOk && currentPasswordHash != null && passwordEncoder.matches(candidate, currentPasswordHash)) {
            violations.add(PasswordViolation.SAME_AS_CURRENT);
        }
        return List.copyOf(violations);
    }

    public static boolean fitsMaxLength(String password) {
        return password.codePointCount(0, password.length()) <= MAX_CODE_POINTS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
