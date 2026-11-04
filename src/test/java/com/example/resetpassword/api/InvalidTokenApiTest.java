package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class InvalidTokenApiTest extends IntegrationTestBase {

    private MockHttpServletResponse confirm(String token, String pw) throws Exception {
        String body = "{\"token\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}".formatted(token, pw, pw);
        return mockMvc.perform(post("/api/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
    }

    @Test
    void allInvalidTokenCasesReturnByteIdenticalProblem() throws Exception {
        List<MockHttpServletResponse> responses = new ArrayList<>();

        String expired = seedToken(TestFixtures.ALICE, TestFixtures.START.minus(Duration.ofMinutes(15)));
        responses.add(confirm(expired, "brand-new-pass"));

        String used = seedToken(TestFixtures.BOB);
        assertThat(confirm(used, "brand-new-pass").getStatus()).isEqualTo(200);
        responses.add(confirm(used, "another-new-pass"));

        String invalidated = seedToken(TestFixtures.ALICE);
        jdbc.update("UPDATE password_reset_token SET invalidated_at = ? WHERE token_hash = ?",
                java.sql.Timestamp.from(TestFixtures.START), tokenGenerator.hash(invalidated));
        responses.add(confirm(invalidated, "brand-new-pass"));

        responses.add(confirm(tokenGenerator.generate(), "brand-new-pass")); // unknown
        responses.add(confirm("not-a-token", "brand-new-pass")); // malformed

        MockHttpServletResponse first = responses.getFirst();
        assertThat(first.getStatus()).isEqualTo(400);
        assertThat(first.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String json = first.getContentAsString();
        assertThat((String) JsonPath.read(json, "$.type")).isEqualTo("/problems/invalid-reset-token");
        assertThat((String) JsonPath.read(json, "$.title")).isEqualTo(TestFixtures.INVALID_LINK_MESSAGE);
        for (MockHttpServletResponse response : responses) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        }
    }

    @Test
    void tokenCheckTakesPrecedenceOverPasswordRules() throws Exception {
        MockHttpServletResponse response = confirm(tokenGenerator.generate(), "short");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.type"))
                .isEqualTo("/problems/invalid-reset-token");
    }
}
