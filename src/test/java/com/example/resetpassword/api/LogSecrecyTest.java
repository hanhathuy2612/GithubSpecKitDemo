package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/** FR-015 / research R9: no token, password or email in logs; audit events are present. */
@ExtendWith(OutputCaptureExtension.class)
class LogSecrecyTest extends IntegrationTestBase {

    private static final String NEW_PASSWORD = "brand-new-pass-xyz";

    @Test
    void fullFlowLogsEventsButNoSecrets(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/password-reset/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\"}")).andExpect(status().isAccepted());
        String token = extractToken(mail.sentTo(TestFixtures.ALICE).getFirst().body());
        String confirm = "{\"token\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                .formatted(token, NEW_PASSWORD, NEW_PASSWORD);
        mockMvc.perform(post("/api/password-reset/confirm").contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/password-reset/confirm").contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isBadRequest());

        String logs = output.getAll();
        assertThat(logs)
                .contains("password_reset.requested", "password_reset.completed", "password_reset.rejected")
                .doesNotContain(token)
                .doesNotContain(tokenGenerator.hash(token))
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain(TestFixtures.SEED_PASSWORD)
                .doesNotContain(TestFixtures.ALICE);
    }
}
