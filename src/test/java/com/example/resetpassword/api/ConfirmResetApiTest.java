package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.service.CredentialService;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ConfirmResetApiTest extends IntegrationTestBase {

    @Autowired
    private CredentialService credentials;

    private ResultActions confirm(String token, String newPassword, String confirmPassword) throws Exception {
        String body = "{\"token\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                .formatted(token, newPassword, confirmPassword);
        return mockMvc.perform(post("/api/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void validRequestResetsPassword() throws Exception {
        String token = seedToken(TestFixtures.ALICE);

        confirm(token, "brand-new-pass", "brand-new-pass")
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"Your password has been reset.\"}", true));

        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, "brand-new-pass")).isTrue();
        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD)).isFalse();
        assertThat(mail.sentTo(TestFixtures.ALICE)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("Your password was changed");
            assertThat(m.body()).doesNotContain(token).doesNotContain("reset-password?token=");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "short,          short,           newPassword,     TOO_SHORT",
            "demo-password-1, demo-password-1, newPassword,    SAME_AS_CURRENT",
            "brand-new-pass, other-new-pass,  confirmPassword, MISMATCH"
    })
    void ruleFailureReturns400AndKeepsTokenUsable(String pw, String confirmPw, String field, String code)
            throws Exception {
        String token = seedToken(TestFixtures.ALICE);

        confirm(token, pw, confirmPw)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/validation"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));

        confirm(token, "brand-new-pass", "brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void tooLongPasswordReturnsTooLong() throws Exception {
        String token = seedToken(TestFixtures.ALICE);
        String longPw = "a".repeat(65);

        confirm(token, longPw, longPw)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    @Test
    void missingFieldsAreValidationErrors() throws Exception {
        mockMvc.perform(post("/api/password-reset/confirm").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation"));
    }
}
