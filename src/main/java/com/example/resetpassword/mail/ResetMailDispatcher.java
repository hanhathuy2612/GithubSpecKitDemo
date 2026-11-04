package com.example.resetpassword.mail;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Sends reset-related email off the request path (research R5). Failures are logged by account id only and never
 * surface to the requester (spec edge case "Email delivery fails", FR-015).
 */
@Component
public class ResetMailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ResetMailDispatcher.class);

    private final ResetEmailComposer composer;
    private final AuthEmailComposer authComposer;
    private final EmailSender sender;

    public ResetMailDispatcher(ResetEmailComposer composer, AuthEmailComposer authComposer, EmailSender sender) {
        this.composer = composer;
        this.authComposer = authComposer;
        this.sender = sender;
    }

    /** 002: registration link for an email that has no account yet (no account id exists). */
    @Async("mailExecutor")
    public void sendRegistrationLink(String email, String rawToken) {
        send(0L, email, authComposer.registrationLink(rawToken), "registration_link");
    }

    @Async("mailExecutor")
    public void sendAttemptedRegistration(long accountId, String email) {
        send(accountId, email, authComposer.attemptedRegistration(), "attempted_registration");
    }

    @Async("mailExecutor")
    public void sendResetLink(long accountId, String email, String rawToken) {
        send(accountId, email, composer.resetLink(rawToken), "reset_link");
    }

    @Async("mailExecutor")
    public void sendPasswordChanged(long accountId, String email, Instant changedAt) {
        send(accountId, email, composer.passwordChanged(changedAt), "password_changed");
    }

    private void send(long accountId, String email, ResetEmailComposer.Email message, String kind) {
        try {
            sender.send(email, message.subject(), message.body());
        } catch (RuntimeException ex) {
            log.warn("password_reset.email_failed kind={} accountId={} error={}",
                    kind, accountId, ex.getClass().getSimpleName());
        }
    }
}
