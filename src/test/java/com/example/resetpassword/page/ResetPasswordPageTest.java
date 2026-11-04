package com.example.resetpassword.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.resetpassword.service.CredentialService;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class ResetPasswordPageTest extends IntegrationTestBase {

    @Autowired
    private CredentialService credentials;

    private ResultActions submit(String token, String pw, String confirmPw) throws Exception {
        return mockMvc.perform(post("/reset-password").param("token", token)
                .param("newPassword", pw).param("confirmPassword", confirmPw));
    }

    @Test
    void getWithUsableTokenRendersFormWithHiddenTokenAndDoesNotConsumeIt() throws Exception {
        String token = seedToken(TestFixtures.ALICE);

        mockMvc.perform(get("/reset-password").param("token", token))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"))
                .andExpect(content().string(containsString("type=\"hidden\" name=\"token\" value=\"" + token + "\"")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));

        submit(token, "brand-new-pass", "brand-new-pass").andExpect(view().name("reset-success"));
    }

    @Test
    void validSubmitRendersSuccessAndChangesPassword() throws Exception {
        String token = seedToken(TestFixtures.ALICE);

        submit(token, "brand-new-pass", "brand-new-pass")
                .andExpect(status().isOk())
                .andExpect(view().name("reset-success"))
                .andExpect(content().string(containsString("Your password has been reset.")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(credentials.verifyCredentials(TestFixtures.ALICE, "brand-new-pass")).isTrue();
    }

    @Test
    void ruleFailureRerendersFormWithErrorAndKeepsToken() throws Exception {
        String token = seedToken(TestFixtures.ALICE);

        submit(token, "brand-new-pass", "different-pass")
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"))
                .andExpect(content().string(containsString("Passwords do not match.")))
                .andExpect(content().string(containsString("value=\"" + token + "\"")));

        submit(token, "brand-new-pass", "brand-new-pass").andExpect(view().name("reset-success"));
    }
}
