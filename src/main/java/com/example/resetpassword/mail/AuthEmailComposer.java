package com.example.resetpassword.mail;

import com.example.resetpassword.config.AppProperties;
import org.springframework.stereotype.Component;

/** 002 registration emails (plan.md › Emails). */
@Component
public class AuthEmailComposer {

    private final AppProperties properties;

    public AuthEmailComposer(AppProperties properties) {
        this.properties = properties;
    }

    public ResetEmailComposer.Email registrationLink(String rawToken) {
        String link = properties.baseUrl() + "/complete-registration?token=" + rawToken;
        String body = """
                Finish creating your account by choosing a password:
                %s

                The link expires in 24 hours and can be used once.

                If you did not ask to create an account, ignore this email.
                """.formatted(link);
        return new ResetEmailComposer.Email("Finish creating your account", body);
    }

    public ResetEmailComposer.Email attemptedRegistration() {
        String body = """
                Someone tried to create an account with your email address, but you already have one.

                If it was you, sign in, or reset your password at:
                %s/forgot-password

                If it was not you, you can ignore this email; nothing has changed.
                """.formatted(properties.baseUrl());
        return new ResetEmailComposer.Email("Someone tried to create an account with your email", body);
    }
}
