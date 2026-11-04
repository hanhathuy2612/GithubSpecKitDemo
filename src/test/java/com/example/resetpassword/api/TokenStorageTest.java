package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** SC-006: only 64-char hex hashes are stored; the raw token appears in no column. */
class TokenStorageTest extends IntegrationTestBase {

    @Test
    void onlyHashesAreStored() throws Exception {
        for (String email : List.of(TestFixtures.ALICE, TestFixtures.BOB)) {
            mockMvc.perform(post("/api/password-reset/requests").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}")).andExpect(status().isAccepted());
        }
        List<String> rawTokens = mail.sent().stream().map(m -> extractToken(m.body())).toList();
        assertThat(rawTokens).hasSize(2);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM password_reset_token");
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("TOKEN_HASH"))).matches("^[0-9a-f]{64}$");
            for (Object value : row.values()) {
                for (String raw : rawTokens) {
                    assertThat(String.valueOf(value)).doesNotContain(raw);
                }
            }
        }
    }
}
