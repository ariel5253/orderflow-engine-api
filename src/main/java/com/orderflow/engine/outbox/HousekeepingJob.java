package com.orderflow.engine.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps idempotency keys, unconfirmed quotes and delivered events from growing without bound (SPEC §12). The three
 * purges are independent and each commits on its own, so a run that stops halfway leaves nothing that the next run
 * cannot finish. One run covers every tenant.
 */
public final class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

    private final HousekeepingStore store;
    private final Clock clock;
    private final Duration idempotencyRetention;
    private final Duration retention;

    /**
     * Purges through {@code store}, with every cutoff read from {@code clock}.
     *
     * @param idempotencyRetention how long an idempotency key is kept
     * @param retention how long expired unconfirmed quotes and SENT outbox events are kept
     */
    public HousekeepingJob(HousekeepingStore store, Clock clock, Duration idempotencyRetention, Duration retention) {
        this.store = store;
        this.clock = clock;
        this.idempotencyRetention = idempotencyRetention;
        this.retention = retention;
    }

    /**
     * The retentions are hours and days long, so an hourly run keeps every table within an hour of its retention.
     * The first run waits one interval, so an instance that has just started serves traffic before it cleans up.
     */
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void purgeExpired() {
        Instant now = clock.instant();
        int keys = store.purgeIdempotencyKeys(now.minus(idempotencyRetention));
        int quotes = store.purgeUnconfirmedQuotes(now.minus(retention));
        int events = store.purgeSentEvents(now.minus(retention));
        LOG.info("Housekeeping purged {} idempotency keys, {} unconfirmed quotes and {} sent outbox events",
                keys, quotes, events);
    }
}
