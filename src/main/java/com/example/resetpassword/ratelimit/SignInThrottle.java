package com.example.resetpassword.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 002 FR-003b / research R7: no fixed lockout. Per email, consecutive failures impose an exponential wait (1 s
 * doubling, max 30 s) before the next evaluated attempt; per IP, at most 20 failures per rolling hour.
 */
@Component
public class SignInThrottle {

    public static final int IP_FAILURE_LIMIT = 20;
    public static final Duration IP_WINDOW = Duration.ofHours(1);
    public static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private record State(int failures, Instant nextAllowedAt) {
    }

    private final Map<String, State> emails = new ConcurrentHashMap<>();
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public SignInThrottle(RateLimiter rateLimiter, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public boolean allowed(String normalizedEmail, String clientIp) {
        if (rateLimiter.isLimited(ipKey(clientIp), IP_FAILURE_LIMIT, IP_WINDOW)) {
            return false;
        }
        State state = emails.get(normalizedEmail);
        return state == null || !clock.instant().isBefore(state.nextAllowedAt());
    }

    public void failure(String normalizedEmail, String clientIp) {
        rateLimiter.record(ipKey(clientIp));
        Instant now = clock.instant();
        emails.compute(normalizedEmail, (key, state) -> {
            int failures = state == null ? 1 : state.failures() + 1;
            return new State(failures, now.plus(waitFor(failures)));
        });
    }

    public void success(String normalizedEmail) {
        emails.remove(normalizedEmail);
    }

    public void reset() {
        emails.clear();
    }

    static Duration waitFor(int failures) {
        long seconds = failures >= 6 ? MAX_WAIT.toSeconds() : Math.min(1L << (failures - 1), MAX_WAIT.toSeconds());
        return Duration.ofSeconds(seconds);
    }

    private static String ipKey(String clientIp) {
        return "signin-ip:" + clientIp;
    }
}
