package com.example.resetpassword.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Research R3: 256-bit CSPRNG tokens, Base64URL (no padding) in links, SHA-256 hex at rest. */
@Component
public class TokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public boolean isWellFormed(String rawToken) {
        return rawToken != null && WELL_FORMED.matcher(rawToken).matches();
    }
}
