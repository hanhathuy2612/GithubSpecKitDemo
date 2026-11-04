package com.example.resetpassword.ratelimit;

import java.time.Duration;

/** FR-014 rate limiting port (research R6). */
public interface RateLimiter {

    /** Records an attempt for {@code key} and returns false if it exceeds {@code limit} within {@code window}. */
    boolean tryAcquire(String key, int limit, Duration window);

    /** Clears all windows (used by tests to isolate state). */
    void reset();
}
