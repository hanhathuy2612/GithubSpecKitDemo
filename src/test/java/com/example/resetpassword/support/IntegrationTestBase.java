package com.example.resetpassword.support;

import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.security.TokenGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for every api/ and page/ test. Resets all shared state before each test so tests are independent
 * (clock, captured mail, rate-limit windows, issued tokens, seeded passwords).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBeansConfig.class)
public abstract class IntegrationTestBase {

    private static final Pattern LINK_TOKEN = Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]{43})");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected FakeEmailSender mail;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TokenGenerator tokenGenerator;

    @Autowired
    private ObjectProvider<RateLimiter> rateLimiters;

    @BeforeEach
    void resetSharedState() {
        clock.setInstant(TestFixtures.START);
        mail.clear();
        rateLimiters.forEach(RateLimiter::reset);
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("UPDATE user_account SET password_hash = ?, status = 'ACTIVE' WHERE email IN (?, ?)",
                TestFixtures.SEED_HASH, TestFixtures.ALICE, TestFixtures.BOB);
        jdbc.update("UPDATE user_account SET password_hash = ?, status = 'DISABLED' WHERE email = ?",
                TestFixtures.SEED_HASH, TestFixtures.DISABLED);
    }

    protected long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM user_account WHERE email = ?", Long.class, email);
    }

    /** Inserts a token for the account directly, returning the raw token (US2 tests do not depend on US1). */
    protected String seedToken(String email, Instant issuedAt) {
        String raw = tokenGenerator.generate();
        jdbc.update("""
                INSERT INTO password_reset_token (account_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, accountId(email), tokenGenerator.hash(raw),
                java.sql.Timestamp.from(issuedAt), java.sql.Timestamp.from(issuedAt.plus(Duration.ofMinutes(15))));
        return raw;
    }

    protected String seedToken(String email) {
        return seedToken(email, clock.instant());
    }

    protected static String extractToken(String emailBody) {
        Matcher matcher = LINK_TOKEN.matcher(emailBody);
        if (!matcher.find()) {
            throw new AssertionError("No reset link in email body");
        }
        return matcher.group(1);
    }
}
