package com.example.resetpassword.page;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class InvalidLinkPageTest extends IntegrationTestBase {

    private static void assertInvalidPage(ResultActions actions) throws Exception {
        actions.andExpect(status().isOk())
                .andExpect(view().name("reset-invalid"))
                .andExpect(content().string(containsString(TestFixtures.INVALID_LINK_MESSAGE)))
                .andExpect(content().string(containsString("href=\"/forgot-password\"")));
    }

    private ResultActions open(String token) throws Exception {
        return mockMvc.perform(get("/reset-password").param("token", token));
    }

    private ResultActions submit(String token) throws Exception {
        return mockMvc.perform(post("/reset-password").param("token", token)
                .param("newPassword", "brand-new-pass").param("confirmPassword", "brand-new-pass"));
    }

    @Test
    void expiredLink() throws Exception {
        String token = seedToken(TestFixtures.ALICE);
        clock.advance(Duration.ofMinutes(15));
        assertInvalidPage(open(token));
        assertInvalidPage(submit(token));
    }

    @Test
    void usedLink() throws Exception {
        String token = seedToken(TestFixtures.ALICE);
        submit(token).andExpect(view().name("reset-success"));
        assertInvalidPage(open(token));
        assertInvalidPage(submit(token));
    }

    @Test
    void invalidatedLink() throws Exception {
        String token = seedToken(TestFixtures.ALICE);
        seedToken(TestFixtures.ALICE); // does not invalidate by itself; simulate a newer request
        jdbc.update("UPDATE password_reset_token SET invalidated_at = ? WHERE token_hash = ?",
                java.sql.Timestamp.from(TestFixtures.START), tokenGenerator.hash(token));
        assertInvalidPage(open(token));
        assertInvalidPage(submit(token));
    }

    @Test
    void unknownAndMalformedLinks() throws Exception {
        assertInvalidPage(open(tokenGenerator.generate()));
        assertInvalidPage(open("garbage"));
        assertInvalidPage(submit("garbage"));
    }

    @Test
    void missingOrBlankToken() throws Exception {
        assertInvalidPage(mockMvc.perform(get("/reset-password")));
        assertInvalidPage(open(""));
        assertInvalidPage(mockMvc.perform(post("/reset-password")
                .param("newPassword", "brand-new-pass").param("confirmPassword", "brand-new-pass")));
    }
}
