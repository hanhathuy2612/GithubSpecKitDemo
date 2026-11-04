package com.example.resetpassword.mail;

import com.example.resetpassword.config.AppProperties;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Builds the two outgoing emails defined in contracts/emails.md. */
@Component
public class ResetEmailComposer {

    public record Email(String subject, String body) {
    }

    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final AppProperties properties;

    public ResetEmailComposer(AppProperties properties) {
        this.properties = properties;
    }

    public Email resetLink(String rawToken) {
        String link = properties.baseUrl() + "/reset-password?token=" + rawToken;
        String body = """
                We received a request to reset your password.

                Open this link to choose a new password:
                %s

                The link expires in 15 minutes and can be used once.

                If you did not request this, you can ignore this email.
                """.formatted(link);
        return new Email("Reset your password", body);
    }

    public Email passwordChanged(Instant changedAt) {
        String body = """
                The password for your account was changed at %s.

                If you did not make this change, contact support immediately.
                """.formatted(UTC_FORMAT.format(changedAt));
        return new Email("Your password was changed", body);
    }
}
