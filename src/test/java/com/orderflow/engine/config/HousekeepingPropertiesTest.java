package com.orderflow.engine.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.engine.config.HousekeepingProperties.Retention;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A retention that is missing, zero or negative puts its cutoff at or after now, so the job would purge rows that are
 * still in use. It must stop the start instead.
 */
class HousekeepingPropertiesTest {

    private static final Retention DAY = new Retention(Duration.ofHours(24));
    private static final Retention WEEK = new Retention(Duration.ofDays(7));

    @Test
    void thePlanDefaultsAreAccepted() {
        assertThatCode(() -> new HousekeepingProperties(DAY, WEEK)).doesNotThrowAnyException();
    }

    @Test
    void anIdempotencyRetentionThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> new HousekeepingProperties(new Retention(Duration.ZERO), WEEK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.idempotency.retention");
        assertThatThrownBy(() -> new HousekeepingProperties(new Retention(Duration.ofHours(-24)), WEEK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.idempotency.retention");
        assertThatThrownBy(() -> new HousekeepingProperties(null, WEEK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.idempotency.retention");
    }

    @Test
    void aHousekeepingRetentionThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> new HousekeepingProperties(DAY, new Retention(Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.housekeeping.retention");
        assertThatThrownBy(() -> new HousekeepingProperties(DAY, new Retention(Duration.ofDays(-7))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.housekeeping.retention");
        assertThatThrownBy(() -> new HousekeepingProperties(DAY, new Retention(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.housekeeping.retention");
    }
}
