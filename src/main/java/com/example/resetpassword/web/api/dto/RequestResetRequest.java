package com.example.resetpassword.web.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Surrounding whitespace is stripped before validation so padded emails are accepted (FR-003). */
public record RequestResetRequest(@NotBlank @Email @Size(max = 254) String email) {

    public RequestResetRequest {
        email = email == null ? null : email.strip();
    }
}
