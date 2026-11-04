package com.example.resetpassword.web.api.dto;

import com.example.resetpassword.service.auth.IssuedCredentials;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
        String refreshExpiresAt) {

    public static TokenResponse from(IssuedCredentials credentials) {
        return new TokenResponse(credentials.accessToken(), "Bearer", credentials.expiresIn(),
                credentials.refreshToken(), credentials.refreshExpiresAt().toString());
    }

    @Override
    public String toString() {
        return "TokenResponse[redacted]";
    }
}
