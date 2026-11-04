package com.example.resetpassword.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Exact "N per rolling window" limiter (research R6). Only allowed attempts are recorded, so a key admits exactly
 * {@code limit} attempts in any window. State is per instance and lost on restart (documented limitation).
 */
@Component
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySlidingWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(window);
        Deque<Instant> attempts = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= limit) {
                return false;
            }
            attempts.addLast(now);
            return true;
        }
    }

    @Override
    public boolean isLimited(String key, int limit, Duration window) {
        Deque<Instant> attempts = windows.get(key);
        if (attempts == null) {
            return false;
        }
        Instant windowStart = clock.instant().minus(window);
        synchronized (attempts) {
            long inWindow = attempts.stream().filter(t -> t.isAfter(windowStart)).count();
            return inWindow >= limit;
        }
    }

    @Override
    public void record(String key) {
        Deque<Instant> attempts = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            attempts.addLast(clock.instant());
        }
    }

    @Override
    public void reset() {
        windows.clear();
    }
}
