package com.example.resetpassword.web.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {

    @Override
    public String toString() {
        return "RefreshRequest[redacted]";
    }
}
