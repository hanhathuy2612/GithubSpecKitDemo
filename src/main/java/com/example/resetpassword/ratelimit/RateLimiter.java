package com.example.resetpassword.ratelimit;

import java.time.Duration;

/** FR-014 rate limiting port (research R6). */
public interface RateLimiter {

    /** Records an attempt for {@code key} and returns false if it exceeds {@code limit} within {@code window}. */
    boolean tryAcquire(String key, int limit, Duration window);

    /** True if {@code key} already has {@code limit} recorded attempts within {@code window}. Records nothing. */
    boolean isLimited(String key, int limit, Duration window);

    /** Records one attempt for {@code key} (for failure-only counting). */
    void record(String key);

    /** Clears all windows (used by tests to isolate state). */
    void reset();
}
