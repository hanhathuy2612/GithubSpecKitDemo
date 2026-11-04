package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class MeApiTest extends IntegrationTestBase {

    private MockHttpServletResponse me(String token) throws Exception {
        var request = get("/api/auth/me");
        if (token != null) {
            request = request.with(bearer(token));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    @Test
    void validTokenReturnsTheAccount() throws Exception {
        String token = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD).accessToken();
        MockHttpServletResponse response = me(token);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(response.getContentAsString(), "$.id")).longValue())
                .isEqualTo(accountId(TestFixtures.ALICE));
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.email")).isEqualTo(TestFixtures.ALICE);
    }

    @Test
    void everyInvalidCredentialGetsTheSame401() throws Exception {
        String valid = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD).accessToken();
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        String noneAlg = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + parts[1] + ".";
        String hs256 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\"}".getBytes())
                + "." + parts[1] + "." + parts[2];
        long aliceId = accountId(TestFixtures.ALICE);
        String wrongIssuer = jwtWith(c -> c.issuer("evil").subject(Long.toString(aliceId)).claim("sid", "x").claim("sv", 0));

        List<MockHttpServletResponse> responses = new ArrayList<>();
        responses.add(me(null));
        responses.add(me(tampered));
        responses.add(me(noneAlg));
        responses.add(me(hs256));
        responses.add(me(wrongIssuer));

        jdbc.update("UPDATE user_account SET session_version = 1 WHERE id = ?", aliceId);
        responses.add(me(valid)); // stale sv
        jdbc.update("UPDATE user_account SET session_version = 0, status = 'DISABLED' WHERE id = ?", aliceId);
        responses.add(me(valid)); // disabled
        jdbc.update("UPDATE user_account SET status = 'ACTIVE' WHERE id = ?", aliceId);
        assertThat(me(valid).getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(15));
        responses.add(me(valid)); // expired

        MockHttpServletResponse first = responses.getFirst();
        assertThat(first.getStatus()).isEqualTo(401);
        assertThat(first.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        String[] names = {"missing", "tampered", "none", "hs256", "wrongIssuer", "staleSv", "disabled", "expired"};
        for (int i = 0; i < responses.size(); i++) {
            MockHttpServletResponse r = responses.get(i);
            assertThat(r.getStatus()).as(names[i]).isEqualTo(401);
            assertThat(r.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(r.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        }
    }

    @Test
    void oneMinuteBeforeExpiryStillWorks() throws Exception {
        String token = signIn(TestFixtures.ALICE, TestFixtures.SEED_PASSWORD).accessToken();
        clock.advance(Duration.ofMinutes(14));
        assertThat(me(token).getStatus()).isEqualTo(200);
    }
}
