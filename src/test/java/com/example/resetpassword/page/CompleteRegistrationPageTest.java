package com.example.resetpassword.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.resetpassword.support.IntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CompleteRegistrationPageTest extends IntegrationTestBase {

    private static final String CAROL = "carol@example.com";
    private static final String INVALID = "This link is invalid or has expired.";

    private String registerAndGetToken(String email) throws Exception {
        int before = mail.sentTo(email).size();
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andExpect(status().isAccepted());
        return extractToken(mail.sentTo(email).get(before).body());
    }

    private ResultActions open(String token) throws Exception {
        return mockMvc.perform(token == null ? get("/complete-registration")
                : get("/complete-registration").param("token", token));
    }

    private ResultActions submit(String token, String pw, String confirm) throws Exception {
        var request = post("/complete-registration").param("password", pw).param("confirmPassword", confirm);
        if (token != null) {
            request = request.param("token", token);
        }
        return mockMvc.perform(request);
    }

    private static void assertInvalid(ResultActions actions) throws Exception {
        actions.andExpect(status().isOk()).andExpect(view().name("registration-invalid"))
                .andExpect(content().string(containsString(INVALID)))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void openShowsFormWithoutConsumingThenSubmitCreatesAccount() throws Exception {
        String token = registerAndGetToken(CAROL);

        open(token).andExpect(view().name("complete-registration"))
                .andExpect(content().string(containsString("type=\"hidden\" name=\"token\" value=\"" + token + "\"")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
        open(token).andExpect(view().name("complete-registration"));

        submit(token, "carol-password", "carol-password")
                .andExpect(view().name("registration-complete"))
                .andExpect(content().string(containsString("Your account is ready. You can now sign in.")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(signIn(CAROL, "carol-password").accessToken()).isNotBlank();
    }

    @Test
    void ruleFailureKeepsFormAndLink() throws Exception {
        String token = registerAndGetToken(CAROL);

        submit(token, "carol-password", "different-pass")
                .andExpect(view().name("complete-registration"))
                .andExpect(content().string(containsString("Passwords do not match.")))
                .andExpect(content().string(containsString("value=\"" + token + "\"")));
        submit(token, "carol-password", "carol-password").andExpect(view().name("registration-complete"));
    }

    @Test
    void invalidLinksAreRejectedOnOpenAndSubmit() throws Exception {
        String used = registerAndGetToken(CAROL);
        submit(used, "carol-password", "carol-password").andExpect(view().name("registration-complete"));
        assertInvalid(open(used));
        assertInvalid(submit(used, "other-password", "other-password"));

        String superseded = registerAndGetToken("dave@example.com");
        registerAndGetToken("dave@example.com");
        assertInvalid(open(superseded));

        String expiring = registerAndGetToken("erin@example.com");
        clock.advance(Duration.ofHours(24));
        assertInvalid(open(expiring));
        assertInvalid(submit(expiring, "erin-password", "erin-password"));

        assertInvalid(open(tokenGenerator.generate()));
        assertInvalid(open("garbage"));
        assertInvalid(open(null));
        assertInvalid(submit(null, "x-password", "x-password"));
    }

    @Test
    void twoConcurrentCompletionsForTheSameEmailCreateOneAccount() throws Exception {
        String first = registerAndGetToken(CAROL);
        clock.advance(Duration.ofMinutes(16)); // past the 15-minute email window, keep both links usable
        jdbc.update("UPDATE registration_token SET invalidated_at = NULL");
        String second = registerAndGetToken(CAROL);
        jdbc.update("UPDATE registration_token SET invalidated_at = NULL");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> views = new ArrayList<>();
            for (String token : List.of(first, second)) {
                views.add(pool.submit(() -> {
                    start.await();
                    return submit(token, "carol-password", "carol-password").andReturn().getModelAndView().getViewName();
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : views) {
                results.add(f.get());
            }
            assertThat(results).containsExactlyInAnyOrder("registration-complete", "registration-invalid");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE email = ?", Integer.class, CAROL))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void takeoverRegressionOnlyTheMailboxOwnerChoosesThePassword() throws Exception {
        // Attacker starts a registration for the victim's address, trying to smuggle a password in.
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"victim@example.com\",\"password\":\"attacker-pass\"}"))
                .andExpect(status().isAccepted());
        String token = extractToken(mail.sentTo("victim@example.com").getFirst().body());

        // The victim clicks the link and chooses their own password.
        submit(token, "victim-own-pass", "victim-own-pass").andExpect(view().name("registration-complete"));

        mockMvc.perform(post("/api/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"victim@example.com\",\"password\":\"attacker-pass\"}"))
                .andExpect(status().isUnauthorized());
        clock.advance(Duration.ofSeconds(1)); // per-email backoff after the attacker's failed attempt
        assertThat(signIn("victim@example.com", "victim-own-pass").accessToken()).isNotBlank();
    }
}
