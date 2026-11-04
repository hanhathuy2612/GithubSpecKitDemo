package com.example.resetpassword.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.ratelimit.SignInThrottle;
import com.example.resetpassword.support.IntegrationTestBase;
import com.example.resetpassword.support.TestFixtures;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** 002 SC-003: unknown vs. wrong password, and new vs. existing registration, differ by ≤ 50 ms (medians). */
@Tag("timing")
class AuthEnumerationTimingTest extends IntegrationTestBase {

    private static final int SAMPLES = 30;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private SignInThrottle throttle;

    private long time(String path, String json) throws Exception {
        rateLimiter.reset();
        throttle.reset();
        long start = System.nanoTime();
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        return System.nanoTime() - start;
    }

    private double median(String path, String json) throws Exception {
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            samples[i] = time(path, json);
        }
        Arrays.sort(samples);
        return (samples[SAMPLES / 2 - 1] + samples[SAMPLES / 2]) / 2.0 / 1_000_000.0;
    }

    @Test
    void signInUnknownEmailVsWrongPassword() throws Exception {
        String unknown = "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}";
        String wrong = "{\"email\":\"alice@example.com\",\"password\":\"wrong-password\"}";
        for (int i = 0; i < 5; i++) {
            time("/api/auth/sign-in", unknown);
            time("/api/auth/sign-in", wrong);
        }
        double u = median("/api/auth/sign-in", unknown);
        double w = median("/api/auth/sign-in", wrong);
        System.out.printf("SC-003 sign-in median unknown=%.2fms wrong=%.2fms%n", u, w);
        assertThat(Math.abs(u - w)).isLessThanOrEqualTo(50.0);
    }

    @Test
    void registrationNewVsExistingEmail() throws Exception {
        String fresh = "{\"email\":\"newcomer@example.com\"}";
        String existing = "{\"email\":\"" + TestFixtures.ALICE + "\"}";
        for (int i = 0; i < 10; i++) {
            time("/api/auth/register", fresh);
            time("/api/auth/register", existing);
        }
        double n = median("/api/auth/register", fresh);
        double e = median("/api/auth/register", existing);
        System.out.printf("SC-003 register median new=%.2fms existing=%.2fms%n", n, e);
        assertThat(Math.abs(n - e)).isLessThanOrEqualTo(50.0);
    }
}
