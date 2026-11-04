package com.example.resetpassword.web.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmResetRequest(@NotBlank String token, @NotNull String newPassword,
        @NotNull String confirmPassword) {

    /** Never print secrets (FR-015). */
    @Override
    public String toString() {
        return "ConfirmResetRequest[redacted]";
    }
}
