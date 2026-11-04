package com.example.resetpassword.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class ForgotPasswordPageTest extends IntegrationTestBase {

    @Test
    void getRendersForm() throws Exception {
        mockMvc.perform(get("/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("forgot-password"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"email\"")));
    }

    @Test
    void validEmailShowsGenericConfirmationAndSendsEmail() throws Exception {
        mockMvc.perform(post("/forgot-password").param("email", TestFixtures.ALICE))
                .andExpect(status().isOk())
                .andExpect(view().name("forgot-password"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(TestFixtures.GENERIC_MESSAGE)));

        assertThat(mail.sentTo(TestFixtures.ALICE)).hasSize(1);
    }

    @Test
    void invalidEmailShowsInlineErrorAndSendsNoEmail() throws Exception {
        mockMvc.perform(post("/forgot-password").param("email", "not-an-email"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Must be a valid email address.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(TestFixtures.GENERIC_MESSAGE))));

        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void knownUnknownAndDisabledEmailsRenderIdenticalPages() throws Exception {
        String alice = submit(TestFixtures.ALICE);
        String nobody = submit(TestFixtures.NOBODY);
        String disabled = submit(TestFixtures.DISABLED);

        assertThat(nobody).isEqualTo(alice);
        assertThat(disabled).isEqualTo(alice);
        assertThat(mail.sent()).hasSize(1);
        assertThat(mail.sentTo(TestFixtures.ALICE)).hasSize(1);
    }

    private String submit(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/forgot-password").param("email", email))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }
}
