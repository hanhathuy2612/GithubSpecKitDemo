package com.example.resetpassword.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.resetpassword.ratelimit.InMemorySlidingWindowRateLimiter;
import com.example.resetpassword.support.MutableClock;
import com.example.resetpassword.support.TestFixtures;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Duration FIFTEEN_MIN = Duration.ofMinutes(15);
    private static final Duration HOUR = Duration.ofHours(1);

    private final MutableClock clock = new MutableClock(TestFixtures.START);
    private final InMemorySlidingWindowRateLimiter limiter = new InMemorySlidingWindowRateLimiter(clock);

    @Test
    void threePerFifteenMinutesForEmailThenAllowedAgainWhenOldestLeavesWindow() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN)).isTrue();
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN)).isFalse();

        clock.setInstant(TestFixtures.START.plus(FIFTEEN_MIN).minusMillis(1));
        assertThat(limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN)).isFalse();
        clock.setInstant(TestFixtures.START.plus(FIFTEEN_MIN));
        assertThat(limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN)).isTrue();
    }

    @Test
    void twentyPerHourForIp() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("ip:10.0.0.1", 20, HOUR)).isTrue();
        }
        assertThat(limiter.tryAcquire("ip:10.0.0.1", 20, HOUR)).isFalse();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN);
        }
        assertThat(limiter.tryAcquire("email:b@example.com", 3, FIFTEEN_MIN)).isTrue();
    }

    @Test
    void resetClearsWindows() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN);
        }
        limiter.reset();
        assertThat(limiter.tryAcquire("email:a@example.com", 3, FIFTEEN_MIN)).isTrue();
    }

    @Test
    void concurrentAcquireNeverExceedsLimit() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire("ip:10.0.0.9", 20, HOUR)) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(20);
    }
}
