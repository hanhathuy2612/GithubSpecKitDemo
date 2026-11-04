package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SignOutApiTest extends IntegrationTestBase {

    private int call(String path, String refreshToken) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn().getResponse().getStatus();
    }

    @Test
    void signOutRevokesOnlyThisSession() throws Exception {
        IssuedCredentials a = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        IssuedCredentials b = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);

        assertThat(call("/api/auth/sign-out", a.refreshToken())).isEqualTo(204);
        assertThat(call("/api/auth/refresh", a.refreshToken())).isEqualTo(401);
        assertThat(call("/api/auth/refresh", b.refreshToken())).isEqualTo(200);
    }

    @Test
    void signOutIsIdempotentAndRevealsNothing() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        String rotatedFrom = creds.refreshToken();
        String current = JsonPath.read(mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rotatedFrom + "\"}")).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");

        assertThat(call("/api/auth/sign-out", tokenGenerator.generate())).isEqualTo(204);
        assertThat(call("/api/auth/sign-out", "garbage")).isEqualTo(204);
        assertThat(call("/api/auth/sign-out", rotatedFrom)).isEqualTo(204);
        assertThat(call("/api/auth/refresh", current)).as("rotated token sign-out changed nothing").isEqualTo(200);
    }

    @Test
    void accessTokenKeepsWorkingUntilItExpires() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        call("/api/auth/sign-out", creds.refreshToken());

        clock.advance(Duration.ofMinutes(14));
        assertThat(mockMvc.perform(get("/api/auth/me").with(bearer(creds.accessToken())))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(1));
        assertThat(mockMvc.perform(get("/api/auth/me").with(bearer(creds.accessToken())))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
