package com.orderflow.engine.outbox;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter. The ceiling starts at {@code base}, doubles after each failed attempt and
 * never exceeds {@code max}. The wait is drawn uniformly between zero and the ceiling, so when a system comes back
 * after an outage, the events that piled up retry spread over time instead of arriving together.
 */
public final class BackoffPolicy {

    private final long baseMillis;
    private final long maxMillis;
    private final RandomGenerator random;

    /**
     * @param random the source of the jitter. It is shared by every poll, so it must be safe to use from any thread
     */
    public BackoffPolicy(Duration base, Duration max, RandomGenerator random) {
        if (base.toMillis() < 1) {
            throw new IllegalArgumentException("The backoff base must be at least one millisecond, was " + base);
        }
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException("The backoff max " + max + " is below the base " + base);
        }
        this.baseMillis = base.toMillis();
        this.maxMillis = max.toMillis();
        this.random = random;
    }

    /** The wait before the next attempt, after {@code attempts} attempts that failed. */
    public Duration delayAfter(int attempts) {
        long ceiling = ceilingAfter(attempts).toMillis();
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }

    Duration ceilingAfter(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("A backoff follows at least one failed attempt, was " + attempts);
        }
        long ceiling = baseMillis;
        for (int doubling = 1; doubling < attempts && ceiling < maxMillis; doubling++) {
            ceiling = Math.min(maxMillis, ceiling * 2);
        }
        return Duration.ofMillis(ceiling);
    }
}
