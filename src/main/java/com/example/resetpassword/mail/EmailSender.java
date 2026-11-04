package com.example.resetpassword.mail;

/** Port for outgoing email (FR-018). Replaced by a capturing fake in tests. */
public interface EmailSender {

    void send(String to, String subject, String body);
}
