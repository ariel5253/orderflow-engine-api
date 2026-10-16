package com.orderflow.engine.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.engine.config.OutboxProperties.Backoff;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A relay that polls without pause, leases for no time or allows no attempt must stop the start, not misbehave. */
class OutboxPropertiesTest {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Backoff BACKOFF = new Backoff(Duration.ofSeconds(2), Duration.ofMinutes(5));

    @Test
    void thePlanDefaultsAreAccepted() {
        assertThatCode(() -> new OutboxProperties(SECOND, Duration.ofSeconds(30), 8, BACKOFF))
                .doesNotThrowAnyException();
    }

    @Test
    void aPollIntervalThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> new OutboxProperties(Duration.ZERO, SECOND, 8, BACKOFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.poll-interval");
    }

    @Test
    void aLeaseThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> new OutboxProperties(SECOND, Duration.ofSeconds(-1), 8, BACKOFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.lease");
        assertThatThrownBy(() -> new OutboxProperties(SECOND, null, 8, BACKOFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.lease");
    }

    @Test
    void lessThanOneAttemptIsRejected() {
        assertThatThrownBy(() -> new OutboxProperties(SECOND, SECOND, 0, BACKOFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.max-attempts");
    }

    @Test
    void aMissingBackoffIsRejected() {
        assertThatThrownBy(() -> new OutboxProperties(SECOND, SECOND, 8, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.backoff");
    }
}
