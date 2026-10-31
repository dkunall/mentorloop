package com.mentorloop.tutor.application;

import java.time.Duration;

/** Fixed-window counter. */
public interface RateLimiter {

    /** True if this call is within {@code limit} for the current window. */
    boolean allow(String bucket, int limit, Duration window);
}
