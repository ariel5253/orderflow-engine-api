package com.orderflow.engine.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The housekeeping retentions, {@code app.idempotency.retention} and {@code app.housekeeping.retention} (plan §2). A
 * retention that is not positive fails the start, because its cutoff would fall at or after now and the job would
 * purge rows that are still in use.
 *
 * @param idempotency how long an idempotency key replays its first response
 * @param housekeeping how long expired unconfirmed quotes and SENT outbox events are kept
 */
@ConfigurationProperties("app")
public record HousekeepingProperties(Retention idempotency, Retention housekeeping) {

    public HousekeepingProperties {
        requirePositive("app.idempotency.retention", idempotency);
        requirePositive("app.housekeeping.retention", housekeeping);
    }

    /** @param retention how long the rows are kept */
    public record Retention(Duration retention) {
    }

    private static void requirePositive(String key, Retention value) {
        Duration retention = value == null ? null : value.retention();
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(key + " must be a positive duration, was " + retention);
        }
    }
}
