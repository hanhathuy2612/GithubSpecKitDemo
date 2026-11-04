package com.example.resetpassword.web.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Email only: a password sent at registration is ignored by design (002 research R8). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email) {

    public RegisterRequest {
        email = email == null ? null : email.strip();
    }
}
