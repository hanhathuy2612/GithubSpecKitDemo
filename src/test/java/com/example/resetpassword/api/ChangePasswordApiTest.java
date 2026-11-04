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
import org.springframework.mock.web.MockHttpServletResponse;

class ChangePasswordApiTest extends IntegrationTestBase {

    private MockHttpServletResponse change(String accessToken, String current, String next, String confirm)
            throws Exception {
        var request = post("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                        .formatted(current, next, confirm));
        if (accessToken != null) {
            request = request.with(bearer(accessToken));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private int me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/auth/me").with(bearer(accessToken))).andReturn().getResponse().getStatus();
    }

    private int refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn().getResponse().getStatus();
    }

    @Test
    void changeRevokesOtherDevicesAndOldTokensButKeepsThisOne() throws Exception {
        IssuedCredentials deviceA = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        IssuedCredentials deviceB = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);

        MockHttpServletResponse response = change(deviceA.accessToken(), TestFixtures.SEED_PASSWORD,
                "brand-new-pass", "brand-new-pass");

        assertThat(response.getStatus()).isEqualTo(200);
        String newAccess = JsonPath.read(response.getContentAsString(), "$.accessToken");
        String newRefresh = JsonPath.read(response.getContentAsString(), "$.refreshToken");
        assertThat(refresh(deviceB.refreshToken())).as("device B refresh").isEqualTo(401);
        assertThat(me(deviceB.accessToken())).as("device B access").isEqualTo(401);
        assertThat(me(deviceA.accessToken())).as("old device A access (stale sv)").isEqualTo(401);
        assertThat(me(newAccess)).as("new device A access").isEqualTo(200);
        assertThat(refresh(newRefresh)).as("new device A refresh").isEqualTo(200);
        assertThat(signIn(TestFixtures.ALICE, "brand-new-pass").accessToken()).isNotBlank();
        assertThat(mail.sentTo(TestFixtures.ALICE)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("Your password was changed");
            assertThat(m.body()).doesNotContain(newRefresh).doesNotContain(newAccess);
        });
    }

    @Test
    void wrongCurrentPasswordIsValidationError() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        MockHttpServletResponse response = change(creds.accessToken(), "wrong-current", "brand-new-pass",
                "brand-new-pass");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.type")).isEqualTo("/problems/validation");
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.errors[0].code"))
                .isEqualTo("WRONG_CURRENT_PASSWORD");
    }

    @Test
    void sixthAttemptWithin15MinutesIs429() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(change(creds.accessToken(), "wrong-current", "brand-new-pass", "brand-new-pass").getStatus())
                    .isEqualTo(400);
        }
        MockHttpServletResponse sixth = change(creds.accessToken(), TestFixtures.SEED_PASSWORD, "brand-new-pass",
                "brand-new-pass");
        assertThat(sixth.getStatus()).isEqualTo(429);
        assertThat((String) JsonPath.read(sixth.getContentAsString(), "$.type")).isEqualTo("/problems/too-many-attempts");

        clock.advance(Duration.ofMinutes(14)); // access token still valid, window still full
        assertThat(change(creds.accessToken(), TestFixtures.SEED_PASSWORD, "brand-new-pass", "brand-new-pass")
                .getStatus()).isEqualTo(429);
    }

    @Test
    void ruleFailureUsesThe001Codes() throws Exception {
        IssuedCredentials creds = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD);
        MockHttpServletResponse response = change(creds.accessToken(), TestFixtures.SEED_PASSWORD, "brand-new-pass",
                "other-new-pass");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.errors[0].code")).isEqualTo("MISMATCH");
    }

    @Test
    void requiresAnAccessToken() throws Exception {
        assertThat(change(null, TestFixtures.SEED_PASSWORD, "brand-new-pass", "brand-new-pass").getStatus())
                .isEqualTo(401);
    }
}
