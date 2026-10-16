package com.orderflow.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Exponential backoff with full jitter: the ceiling doubles per failed attempt up to the maximum, and the wait is drawn
 * uniformly between zero and that ceiling, so the retries of many events after one outage spread out.
 */
class BackoffPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofMinutes(5);

    @Test
    void theCeilingStartsAtTheBaseAndDoublesAfterEachFailedAttempt() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, new Random());

        assertThat(policy.ceilingAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.ceilingAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.ceilingAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.ceilingAfter(8)).isEqualTo(Duration.ofSeconds(256));
    }

    @Test
    void theCeilingNeverExceedsTheMaximumHoweverManyAttemptsFailed() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, new Random());

        assertThat(policy.ceilingAfter(9)).isEqualTo(MAX);
        assertThat(policy.ceilingAfter(100)).isEqualTo(MAX);
        assertThat(policy.ceilingAfter(Integer.MAX_VALUE)).isEqualTo(MAX);
    }

    @Test
    void theDelayCanBeAsLongAsTheCeiling() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, Draws.highest());

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delayAfter(20)).isEqualTo(MAX);
    }

    @Test
    void theDelayCanBeZero() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, Draws.lowest());

        assertThat(policy.delayAfter(1)).isZero();
        assertThat(policy.delayAfter(20)).isZero();
    }

    @Test
    void theDelayIsDrawnBetweenZeroAndTheCeiling() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, new Random(42));

        for (int attempts = 1; attempts <= 12; attempts++) {
            Duration ceiling = policy.ceilingAfter(attempts);
            for (int draw = 0; draw < 200; draw++) {
                assertThat(policy.delayAfter(attempts)).isBetween(Duration.ZERO, ceiling);
            }
        }
    }

    @Test
    void anAttemptCountBelowOneIsRejected() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, new Random());

        assertThatThrownBy(() -> policy.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMaximumBelowTheBaseIsRejected() {
        Random random = new Random();

        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(10), Duration.ofSeconds(5), random))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBaseOfLessThanOneMillisecondIsRejected() {
        Random random = new Random();

        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, MAX, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofNanos(1), MAX, random))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
