package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 002 US5 / FR-017: a 001 password reset signs the account out everywhere. */
class ResetRevokesSessionsTest extends IntegrationTestBase {

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int refresh(String token) throws Exception {
        return status(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}"));
    }

    @Test
    void resetRevokesAllSessionsAndAccessTokens() throws Exception {
        IssuedCredentials one = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        IssuedCredentials two = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        String resetToken = seedToken(TestFixtures.ALICE);

        assertThat(status(post("/api/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"after-reset-pass\",\"confirmPassword\":\"after-reset-pass\"}"
                        .formatted(resetToken)))).isEqualTo(200);

        assertThat(refresh(one.refreshToken())).isEqualTo(401);
        assertThat(refresh(two.refreshToken())).isEqualTo(401);
        assertThat(status(get("/api/auth/me").with(bearer(one.accessToken())))).isEqualTo(401);
        assertThat(status(get("/api/auth/me").with(bearer(two.accessToken())))).isEqualTo(401);
        clock.advance(Duration.ofSeconds(1));
        assertThat(signIn(TestFixtures.ALICE, "after-reset-pass").accessToken()).isNotBlank();
    }
}
