package com.example.resetpassword.web.api.dto;

import jakarta.validation.constraints.NotNull;

public record ChangePasswordRequest(@NotNull String currentPassword, @NotNull String newPassword,
        @NotNull String confirmPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
