package com.example.resetpassword.support;

import com.example.resetpassword.mail.EmailSender;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures sent messages for assertions (FR-018). Can be switched to fail every send. */
public class FakeEmailSender implements EmailSender {

    public record SentEmail(String to, String subject, String body, Instant capturedAt) {
    }

    private final List<SentEmail> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void send(String to, String subject, String body) {
        if (failing) {
            throw new IllegalStateException("simulated SMTP failure");
        }
        sent.add(new SentEmail(to, subject, body, Instant.now()));
    }

    public List<SentEmail> sent() {
        return List.copyOf(sent);
    }

    public List<SentEmail> sentTo(String email) {
        return sent.stream().filter(m -> m.to().equals(email)).toList();
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void clear() {
        sent.clear();
        failing = false;
    }
}
