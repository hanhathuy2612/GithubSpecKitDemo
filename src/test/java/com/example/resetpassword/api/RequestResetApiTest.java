package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RequestResetApiTest extends IntegrationTestBase {

    private static final String URL = "/api/password-reset/requests";

    @Test
    void knownEmailReturns202AndSendsOneEmailWithLink() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{\"message\":\"" + TestFixtures.GENERIC_MESSAGE + "\"}", true));

        assertThat(mail.sentTo(TestFixtures.ALICE)).hasSize(1);
        String token = extractToken(mail.sentTo(TestFixtures.ALICE).getFirst().body());
        assertThat(token).hasSize(43);
        Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ?",
                Integer.class, tokenGenerator.hash(token));
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void mixedCaseAndPaddedEmailIsSameAccount() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  Alice@Example.COM  \"}"))
                .andExpect(status().isAccepted());

        assertThat(mail.sentTo(TestFixtures.ALICE)).hasSize(1);
    }

    @Test
    void invalidEmailReturns400ProblemAndNoEmail() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void missingEmailReturns400ProblemAndNoEmail() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void emailLongerThan254IsRejected() throws Exception {
        String longEmail = "a".repeat(250) + "@example.com";
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + longEmail + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void secondRequestInvalidatesFirstLink() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"alice@example.com\"}")).andExpect(status().isAccepted());
        }
        String first = extractToken(mail.sentTo(TestFixtures.ALICE).get(0).body());
        String second = extractToken(mail.sentTo(TestFixtures.ALICE).get(1).body());

        assertThat(jdbc.queryForObject("SELECT invalidated_at IS NOT NULL FROM password_reset_token WHERE token_hash = ?",
                Boolean.class, tokenGenerator.hash(first))).isTrue();
        assertThat(jdbc.queryForObject("SELECT invalidated_at IS NULL FROM password_reset_token WHERE token_hash = ?",
                Boolean.class, tokenGenerator.hash(second))).isTrue();
    }
}
