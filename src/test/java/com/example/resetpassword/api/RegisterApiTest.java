package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class RegisterApiTest extends IntegrationTestBase {

    private static final String CAROL = "carol@example.com";

    private MockHttpServletResponse register(String json, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andReturn().getResponse();
    }

    private MockHttpServletResponse register(String email) throws Exception {
        return register("{\"email\":\"" + email + "\"}", "10.0.0.1");
    }

    @Test
    void everyEmailStateGetsTheIdenticalResponseAndTheRightEmail() throws Exception {
        MockHttpServletResponse fresh = register(CAROL);
        MockHttpServletResponse pending = register(CAROL);
        MockHttpServletResponse active = register(TestFixtures.ALICE);
        MockHttpServletResponse disabled = register(TestFixtures.DISABLED);

        assertThat(fresh.getStatus()).isEqualTo(202);
        assertThat((String) JsonPath.read(fresh.getContentAsString(), "$.message"))
                .isEqualTo("Check your email to finish creating your account.");
        assertThat(pending.getContentAsByteArray()).isEqualTo(fresh.getContentAsByteArray());
        assertThat(active.getContentAsByteArray()).isEqualTo(fresh.getContentAsByteArray());
        assertThat(disabled.getContentAsByteArray()).isEqualTo(fresh.getContentAsByteArray());

        assertThat(mail.sentTo(CAROL)).hasSize(2).allSatisfy(m -> assertThat(m.body()).contains("/complete-registration?token="));
        assertThat(mail.sentTo(TestFixtures.ALICE)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("Someone tried to create an account with your email");
            assertThat(m.body()).doesNotContain("complete-registration");
        });
        assertThat(mail.sentTo(TestFixtures.DISABLED)).isEmpty();
    }

    @Test
    void invalidEmailIsValidationError() throws Exception {
        MockHttpServletResponse response = register("not-an-email");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.type")).isEqualTo("/problems/validation");
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void fourthRegistrationWithin15MinutesIsSilentlyDropped() throws Exception {
        MockHttpServletResponse first = null;
        for (int i = 0; i < 4; i++) {
            MockHttpServletResponse r = register("{\"email\":\"" + CAROL + "\"}", "10.0.0." + i);
            first = first == null ? r : first;
            assertThat(r.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        }
        assertThat(mail.sentTo(CAROL)).hasSize(3);
    }

    @Test
    void noAccountExistsUntilCompletionAndPasswordFieldsAreIgnored() throws Exception {
        register("{\"email\":\"" + CAROL + "\",\"password\":\"attacker-pass\"}", "10.0.0.1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE email = ?", Integer.class, CAROL))
                .isZero();
    }
}
