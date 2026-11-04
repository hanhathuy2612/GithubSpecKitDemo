package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** Spec edge case: delivery failure is invisible to the requester and recorded for operators. */
@ExtendWith(OutputCaptureExtension.class)
class EmailFailureApiTest extends IntegrationTestBase {

    private MockHttpServletResponse request(String email) throws Exception {
        return mockMvc.perform(post("/api/password-reset/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn().getResponse();
    }

    @Test
    void failingSmtpStillReturnsIdentical202AndLogsWithoutSecrets(CapturedOutput output) throws Exception {
        MockHttpServletResponse unknown = request(TestFixtures.NOBODY);
        mail.setFailing(true);

        MockHttpServletResponse known = request(TestFixtures.ALICE);

        assertThat(known.getStatus()).isEqualTo(202);
        assertThat(known.getContentAsByteArray()).isEqualTo(unknown.getContentAsByteArray());
        assertThat(output.getAll())
                .contains("password_reset.email_failed")
                .doesNotContain(TestFixtures.ALICE)
                .doesNotContain("reset-password?token=");
    }
}
