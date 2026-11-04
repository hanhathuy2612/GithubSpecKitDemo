package com.example.resetpassword.service.auth;

import java.time.Instant;

/** A fresh access + refresh token pair. The raw refresh token exists only here, never in storage. */
public record IssuedCredentials(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt) {

    @Override
    public String toString() {
        return "IssuedCredentials[redacted]";
    }
}
