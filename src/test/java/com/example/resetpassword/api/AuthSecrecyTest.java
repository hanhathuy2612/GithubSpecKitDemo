package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.service.auth.IssuedCredentials;
import com.example.resetpassword.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/** 002 FR-018 / SC-005: no raw token, password or email in logs; only hashes at rest. */
@ExtendWith(OutputCaptureExtension.class)
class AuthSecrecyTest extends IntegrationTestBase {

    private static final String FRANK = "frank@example.com";
    private static final String PASSWORD = "frank-first-pass";
    private static final String NEW_PASSWORD = "frank-second-pass";

    @Test
    void fullFlowLeaksNothing(CapturedOutput output) throws Exception {
        List<String> secrets = new ArrayList<>(List.of(FRANK, PASSWORD, NEW_PASSWORD));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + FRANK + "\"}"));
        String registrationToken = extractToken(mail.sentTo(FRANK).getFirst().body());
        secrets.add(registrationToken);
        mockMvc.perform(post("/complete-registration").param("token", registrationToken)
                .param("password", PASSWORD).param("confirmPassword", PASSWORD));

        IssuedCredentials creds = signIn(FRANK, PASSWORD);
        secrets.add(creds.accessToken());
        secrets.add(creds.refreshToken());
        String refreshed = mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + creds.refreshToken() + "\"}")).andReturn().getResponse()
                .getContentAsString();
        String access2 = JsonPath.read(refreshed, "$.accessToken");
        String refresh2 = JsonPath.read(refreshed, "$.refreshToken");
        secrets.add(access2);
        secrets.add(refresh2);
        String changed = mockMvc.perform(post("/api/auth/password").with(bearer(access2))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                        .formatted(PASSWORD, NEW_PASSWORD, NEW_PASSWORD))).andReturn().getResponse()
                .getContentAsString();
        String refresh3 = JsonPath.read(changed, "$.refreshToken");
        secrets.add(JsonPath.read(changed, "$.accessToken"));
        secrets.add(refresh3);
        mockMvc.perform(post("/api/auth/sign-out").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh3 + "\"}"));

        String logs = output.getAll();
        assertThat(logs).contains("auth.registered", "auth.registration_completed", "auth.sign_in",
                "auth.refresh", "auth.password_changed", "auth.sign_out");
        for (String secret : secrets) {
            assertThat(logs).doesNotContain(secret);
        }

        for (String table : List.of("refresh_token", "registration_token")) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table);
            assertThat(rows).isNotEmpty();
            for (Map<String, Object> row : rows) {
                assertThat(String.valueOf(row.get("TOKEN_HASH"))).matches("^[0-9a-f]{64}$");
                for (Object value : row.values()) {
                    // The email is stored by design (registration_token.email); tokens and passwords never are.
                    for (String secret : secrets.subList(1, secrets.size())) {
                        assertThat(String.valueOf(value)).doesNotContain(secret);
                    }
                }
            }
        }
    }
}
