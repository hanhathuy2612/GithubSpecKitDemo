package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class EnumerationApiTest extends IntegrationTestBase {

    private MockHttpServletResponse request(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/password-reset/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}")
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        }))
                .andReturn().getResponse();
    }

    private static void assertIdentical(MockHttpServletResponse actual, MockHttpServletResponse expected)
            throws Exception {
        assertThat(actual.getStatus()).isEqualTo(expected.getStatus()).isEqualTo(202);
        assertThat(actual.getContentType()).isEqualTo(expected.getContentType());
        assertThat(actual.getContentAsByteArray()).isEqualTo(expected.getContentAsByteArray());
    }

    @Test
    void knownUnknownAndDisabledResponsesAreByteIdentical() throws Exception {
        MockHttpServletResponse known = request(TestFixtures.ALICE, "10.0.0.1");
        MockHttpServletResponse unknown = request(TestFixtures.NOBODY, "10.0.0.1");
        MockHttpServletResponse disabled = request(TestFixtures.DISABLED, "10.0.0.1");

        assertIdentical(unknown, known);
        assertIdentical(disabled, known);
        assertThat(mail.sent()).singleElement().satisfies(m -> assertThat(m.to()).isEqualTo(TestFixtures.ALICE));
    }

    @Test
    void fourthRequestForSameEmailWithin15MinutesIsSilentlyDropped() throws Exception {
        MockHttpServletResponse first = request(TestFixtures.ALICE, "10.0.0.1");
        request(TestFixtures.ALICE, "10.0.0.2");
        request(TestFixtures.ALICE, "10.0.0.3");
        MockHttpServletResponse fourth = request(TestFixtures.ALICE, "10.0.0.4");

        assertIdentical(fourth, first);
        assertThat(mail.sentTo(TestFixtures.ALICE)).hasSize(3);
    }

    @Test
    void twentyFirstRequestFromSameIpWithinHourIsSilentlyDropped() throws Exception {
        MockHttpServletResponse first = null;
        for (int i = 0; i < 20; i++) {
            MockHttpServletResponse r = request("user" + i + "@example.com", "10.0.0.7");
            first = first == null ? r : first;
        }
        MockHttpServletResponse twentyFirst = request(TestFixtures.ALICE, "10.0.0.7");

        assertIdentical(twentyFirst, first);
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void unknownEmailsConsumeIpSlotsToo() throws Exception {
        for (int i = 0; i < 20; i++) {
            request(TestFixtures.NOBODY.replace("nobody", "nobody" + i), "10.0.0.8");
        }
        request(TestFixtures.BOB, "10.0.0.8");

        assertThat(mail.sent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token", Integer.class)).isZero();
    }

    @Test
    void unknownEmailConsumesEmailSlots() throws Exception {
        for (int i = 0; i < 3; i++) {
            request(TestFixtures.NOBODY, "10.0.1." + i);
        }
        // The window for nobody@ is full; the same must apply to known emails, which keeps behavior symmetric.
        for (int i = 0; i < 3; i++) {
            request(TestFixtures.BOB, "10.0.2." + i);
        }
        request(TestFixtures.BOB, "10.0.2.9");
        assertThat(mail.sentTo(TestFixtures.BOB)).hasSize(3);
    }
}
