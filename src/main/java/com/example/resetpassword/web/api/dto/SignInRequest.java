package com.example.resetpassword.web.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignInRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull String password) {

    public SignInRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "SignInRequest[redacted]";
    }
}
