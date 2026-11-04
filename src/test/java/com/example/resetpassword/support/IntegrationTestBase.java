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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import com.example.resetpassword.service.auth.IssuedCredentials;
import com.jayway.jsonpath.JsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for every api/ and page/ test. Resets all shared state before each test so tests are independent
 * (clock, captured mail, rate-limit windows, issued tokens, seeded passwords).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBeansConfig.class)
public abstract class IntegrationTestBase {

    private static final Pattern LINK_TOKEN =
            Pattern.compile("/(?:reset-password|complete-registration)\\?token=([A-Za-z0-9_-]{43})");

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

    @Autowired
    private ObjectProvider<TestResettable> resettables;

    @Autowired
    protected JwtEncoder jwtEncoder;

    @BeforeEach
    void resetSharedState() {
        clock.setInstant(TestFixtures.START);
        mail.clear();
        rateLimiters.forEach(RateLimiter::reset);
        // FK-safe order (002 tasks T012).
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM registration_token");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM user_account WHERE email NOT IN (?, ?, ?)",
                TestFixtures.ALICE, TestFixtures.BOB, TestFixtures.DISABLED);
        jdbc.update("UPDATE user_account SET password_hash = ?, status = 'ACTIVE', session_version = 0"
                + " WHERE email IN (?, ?)", TestFixtures.SEED_HASH, TestFixtures.ALICE, TestFixtures.BOB);
        jdbc.update("UPDATE user_account SET password_hash = ?, status = 'DISABLED', session_version = 0"
                + " WHERE email = ?", TestFixtures.SEED_HASH, TestFixtures.DISABLED);
        resettables.forEach(TestResettable::reset);
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

    /** Signs in through the API and returns the parsed credentials (fails the test on non-200). */
    protected IssuedCredentials signIn(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new IssuedCredentials(JsonPath.read(body, "$.accessToken"),
                ((Number) JsonPath.read(body, "$.expiresIn")).longValue(), JsonPath.read(body, "$.refreshToken"),
                Instant.parse(JsonPath.read(body, "$.refreshExpiresAt")));
    }

    protected static RequestPostProcessor bearer(String accessToken) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + accessToken);
            return request;
        };
    }

    /** Mints a token with the application's signing key and arbitrary claims, for tampering tests. */
    protected String jwtWith(java.util.function.Consumer<JwtClaimsSet.Builder> claims) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("reset-password-demo")
                .issuedAt(clock.instant())
                .expiresAt(clock.instant().plus(Duration.ofMinutes(15)));
        claims.accept(builder);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId("dev").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    protected static String extractToken(String emailBody) {
        Matcher matcher = LINK_TOKEN.matcher(emailBody);
        if (!matcher.find()) {
            throw new AssertionError("No reset link in email body");
        }
        return matcher.group(1);
    }
}
