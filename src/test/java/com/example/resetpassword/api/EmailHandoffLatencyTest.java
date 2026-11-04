package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** SC-005: with the real bounded async executor, mail is handed to the sender within 5 s of the request at p95. */
@TestPropertySource(properties = "app.mail-async=true")
class EmailHandoffLatencyTest extends IntegrationTestBase {

    private static final int REQUESTS = 20;

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    void p95HandoffWithinFiveSeconds() throws Exception {
        List<Long> latenciesMillis = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            String email = i % 2 == 0 ? TestFixtures.ALICE : TestFixtures.BOB;
            rateLimiter.reset();
            int before = mail.sentTo(email).size();
            Instant requested = Instant.now();
            mockMvc.perform(post("/api/password-reset/requests").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}")).andExpect(status().isAccepted());
            Instant deadline = Instant.now().plusSeconds(10);
            while (mail.sentTo(email).size() == before && Instant.now().isBefore(deadline)) {
                Thread.sleep(5);
            }
            assertThat(mail.sentTo(email)).as("email %d delivered", i).hasSize(before + 1);
            Instant captured = mail.sentTo(email).getLast().capturedAt();
            latenciesMillis.add(Duration.between(requested, captured).toMillis());
        }
        latenciesMillis.sort(Long::compare);
        long p95 = latenciesMillis.get((int) Math.ceil(REQUESTS * 0.95) - 1);
        System.out.printf("SC-005 hand-off p95=%dms%n", p95);
        assertThat(p95).isLessThanOrEqualTo(5_000L);
    }
}
