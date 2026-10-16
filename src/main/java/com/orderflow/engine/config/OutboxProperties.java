package com.orderflow.engine.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The outbox relay settings, {@code app.outbox.*} (plan §2). A wrong value fails the start, because a relay that
 * polls with a zero interval or allows no attempt would silently stop delivering.
 *
 * @param pollInterval how long the relay waits between two polls
 * @param lease how long a claimed event stays leased before another relay may claim it again
 * @param maxAttempts delivery attempts before an event becomes DEAD
 * @param backoff the bounds of the wait between two attempts
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(Duration pollInterval, Duration lease, int maxAttempts, Backoff backoff) {

    public OutboxProperties {
        requirePositive("app.outbox.poll-interval", pollInterval);
        requirePositive("app.outbox.lease", lease);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.outbox.max-attempts must be at least 1, was " + maxAttempts);
        }
        if (backoff == null) {
            throw new IllegalArgumentException("app.outbox.backoff.base and app.outbox.backoff.max are required");
        }
    }

    /**
     * The backoff policy checks these bounds itself.
     *
     * @param base the longest wait after the first failed attempt, doubled after each further one
     * @param max the longest wait ever, however many attempts failed
     */
    public record Backoff(Duration base, Duration max) {
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + " must be a positive duration, was " + value);
        }
    }
}
