package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class SignInApiTest extends IntegrationTestBase {

    private MockHttpServletResponse signInRaw(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        }))
                .andReturn().getResponse();
    }

    private static String decodePart(String jwt, int index) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]), StandardCharsets.UTF_8);
    }

    @Test
    void successReturnsBearerPairWithoutPii() throws Exception {
        MockHttpServletResponse response = signInRaw(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD, "10.0.0.1");

        assertThat(response.getStatus()).isEqualTo(200);
        String body = response.getContentAsString();
        assertThat((String) JsonPath.read(body, "$.tokenType")).isEqualTo("Bearer");
        assertThat(((Number) JsonPath.read(body, "$.expiresIn")).intValue()).isEqualTo(900);
        assertThat((String) JsonPath.read(body, "$.refreshToken")).hasSize(43);
        String jwt = JsonPath.read(body, "$.accessToken");
        String header = decodePart(jwt, 0);
        String claims = decodePart(jwt, 1);
        assertThat(header).contains("\"alg\":\"ES256\"").contains("\"kid\":\"dev\"");
        assertThat(claims).contains("\"sub\":\"" + accountId(TestFixtures.ALICE) + "\"", "\"sid\":", "\"sv\":0",
                "\"iss\":\"reset-password-demo\"").doesNotContain(TestFixtures.ALICE);
    }

    @Test
    void everyFailureCauseIsByteIdentical() throws Exception {
        MockHttpServletResponse wrong = signInRaw(TestFixtures.ALICE, "wrong-password", "10.0.0.1");
        MockHttpServletResponse unknown = signInRaw(TestFixtures.NOBODY, "wrong-password", "10.0.0.2");
        MockHttpServletResponse disabled = signInRaw(TestFixtures.DISABLED, TestFixtures.SEED_PASSWORD, "10.0.0.3");

        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(wrong.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat((String) JsonPath.read(wrong.getContentAsString(), "$.type")).isEqualTo("/problems/invalid-credentials");
        assertThat((String) JsonPath.read(wrong.getContentAsString(), "$.title")).isEqualTo("Email or password is incorrect.");
        assertThat(unknown.getContentAsByteArray()).isEqualTo(wrong.getContentAsByteArray());
        assertThat(disabled.getContentAsByteArray()).isEqualTo(wrong.getContentAsByteArray());
    }

    @Test
    void malformedBodyIsValidationError() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.type")).isEqualTo("/problems/validation");
    }

    @Test
    void ipCapBlocksEvenTheCorrectPassword() throws Exception {
        MockHttpServletResponse reference = signInRaw(TestFixtures.NOBODY, "x-wrong-pass", "10.0.0.50");
        for (int i = 1; i < 21; i++) {
            signInRaw("user" + i + "@example.com", "x-wrong-pass", "10.0.0.50");
        }
        MockHttpServletResponse blocked = signInRaw(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD, "10.0.0.50");

        assertThat(blocked.getStatus()).isEqualTo(401);
        assertThat(blocked.getContentAsByteArray()).isEqualTo(reference.getContentAsByteArray());
    }

    @Test
    void perEmailBackoffEndToEnd() throws Exception {
        signInRaw(TestFixtures.ALICE, "wrong-password", "10.0.0.1");
        clock.advance(Duration.ofSeconds(1));
        MockHttpServletResponse second = signInRaw(TestFixtures.ALICE, "wrong-password", "10.0.0.1");
        MockHttpServletResponse immediate = signInRaw(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD, "10.0.0.1");

        assertThat(immediate.getStatus()).isEqualTo(401);
        assertThat(immediate.getContentAsByteArray()).isEqualTo(second.getContentAsByteArray());

        clock.advance(Duration.ofSeconds(2));
        assertThat(signInRaw(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD, "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void paddedMixedCaseEmailSignsIn() throws Exception {
        assertThat(signInRaw("  Alice@Example.COM ", TestFixtures.SEED_PASSWORD, "10.0.0.1").getStatus())
                .isEqualTo(200);
    }
}
