package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** SC-004: median latency for known vs unknown emails differs by at most 50 ms. Run with -PincludeTags=timing. */
@Tag("timing")
class EnumerationTimingTest extends IntegrationTestBase {

    private static final int SAMPLES = 50;

    @Autowired
    private RateLimiter rateLimiter;

    private long timeRequestNanos(String email) throws Exception {
        rateLimiter.reset();
        long start = System.nanoTime();
        mockMvc.perform(post("/api/password-reset/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andExpect(status().isAccepted());
        return System.nanoTime() - start;
    }

    private double medianMillis(String email) throws Exception {
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            samples[i] = timeRequestNanos(email);
        }
        Arrays.sort(samples);
        return (samples[SAMPLES / 2 - 1] + samples[SAMPLES / 2]) / 2.0 / 1_000_000.0;
    }

    @Test
    void knownAndUnknownMediansDifferByAtMost50Ms() throws Exception {
        for (int i = 0; i < 20; i++) { // warm-up
            timeRequestNanos(TestFixtures.ALICE);
            timeRequestNanos(TestFixtures.NOBODY);
        }
        double known = medianMillis(TestFixtures.ALICE);
        double unknown = medianMillis(TestFixtures.NOBODY);

        System.out.printf("SC-004 median known=%.2fms unknown=%.2fms%n", known, unknown);
        assertThat(Math.abs(known - unknown)).isLessThanOrEqualTo(50.0);
    }
}
