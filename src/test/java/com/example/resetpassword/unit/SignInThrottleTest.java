package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.resetpassword.ratelimit.InMemorySlidingWindowRateLimiter;
import com.example.resetpassword.ratelimit.SignInThrottle;
import com.example.resetpassword.support.MutableClock;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SignInThrottleTest {

    private final MutableClock clock = new MutableClock(TestFixtures.START);
    private final SignInThrottle throttle =
            new SignInThrottle(new InMemorySlidingWindowRateLimiter(clock), clock);

    @Test
    void backoffDoublesUpToThirtySeconds() {
        long[] expectedWaits = {1, 2, 4, 8, 16, 30, 30};
        for (long wait : expectedWaits) {
            throttle.failure("a@example.com", "10.0.0.1");
            clock.advance(Duration.ofSeconds(wait).minusMillis(1));
            assertThat(throttle.allowed("a@example.com", "10.0.0.1")).as("blocked within %ss", wait).isFalse();
            clock.advance(Duration.ofMillis(1));
            assertThat(throttle.allowed("a@example.com", "10.0.0.1")).as("allowed after %ss", wait).isTrue();
        }
    }

    @Test
    void successResetsTheEmailCounter() {
        throttle.failure("a@example.com", "10.0.0.1");
        throttle.failure("a@example.com", "10.0.0.1");
        throttle.success("a@example.com");
        assertThat(throttle.allowed("a@example.com", "10.0.0.1")).isTrue();
        throttle.failure("a@example.com", "10.0.0.1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.allowed("a@example.com", "10.0.0.1")).isTrue();
    }

    @Test
    void unknownEmailsAreTrackedTheSameWay() {
        throttle.failure("nobody@example.com", "10.0.0.1");
        assertThat(throttle.allowed("nobody@example.com", "10.0.0.1")).isFalse();
    }

    @Test
    void twentyFailuresFromOneIpBlockTheIpForAnHour() {
        for (int i = 0; i < 20; i++) {
            throttle.failure("user" + i + "@example.com", "10.0.0.9");
        }
        assertThat(throttle.allowed("fresh@example.com", "10.0.0.9")).isFalse();
        assertThat(throttle.allowed("fresh@example.com", "10.0.0.10")).isTrue();
        clock.advance(Duration.ofHours(1));
        assertThat(throttle.allowed("fresh@example.com", "10.0.0.9")).isTrue();
    }

    @Test
    void ipSuccessesAreNotCounted() {
        for (int i = 0; i < 50; i++) {
            throttle.success("a@example.com");
        }
        assertThat(throttle.allowed("a@example.com", "10.0.0.1")).isTrue();
    }
}
