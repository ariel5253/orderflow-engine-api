package com.orderflow.engine.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers the outbox events of confirmed orders to push and the ERP (SPEC §7.4).
 *
 * <p>Each poll claims the due events with a lease in one short statement that commits at once, then calls the
 * gateways with no database transaction open (RF-8), then records each outcome. Delivery is at least once: a relay
 * that stops between send and mark leaves the lease to expire, and the event is sent again. Consumers deduplicate by
 * event id, so the effect still happens once. A failure that is worth retrying waits for an exponential backoff with
 * full jitter, and the event becomes DEAD after {@code maxAttempts} failed attempts, or at once when retrying would
 * fail the same way.
 *
 * <p>Each order has one event per destination, so there is nothing to reorder within an order, and an outage of one
 * destination never holds back the other.
 */
public final class OutboxRelay {

    /**
     * The events one poll claims. A batch is sent one event at a time, so it is kept small enough to be delivered well
     * within one lease, and the rest waits for the next poll.
     */
    private static final int BATCH_SIZE = 20;

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final PushNotificationPort push;
    private final ErpPublisherPort erp;
    private final BackoffPolicy backoff;
    private final Duration lease;
    private final int maxAttempts;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, PushNotificationPort push, ErpPublisherPort erp,
            BackoffPolicy backoff, Duration lease, int maxAttempts, Clock clock) {
        this.repository = repository;
        this.push = push;
        this.erp = erp;
        this.backoff = backoff;
        this.lease = lease;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    /** Delivers the events that are due now and returns how many were claimed. */
    public int relayDue() {
        Instant now = clock.instant();
        List<ClaimedEvent> claimed = repository.claimDue(now, now.plus(lease), BATCH_SIZE);
        claimed.forEach(this::deliver);
        return claimed.size();
    }

    private void deliver(ClaimedEvent claimed) {
        try {
            send(claimed);
        } catch (DeliveryException failure) {
            failed(claimed, failure.getMessage(), failure.retryable());
            return;
        } catch (RuntimeException failure) {
            // A failure the gateway did not classify, such as a timeout: the next attempt may well succeed.
            failed(claimed, failure.toString(), true);
            return;
        }
        if (repository.markSent(claimed)) {
            LOG.debug("Outbox event {} to {} sent for order {}", eventId(claimed), claimed.destination(),
                    orderNumber(claimed));
        } else {
            leaseLost(claimed);
        }
    }

    private void send(ClaimedEvent claimed) {
        switch (claimed.destination()) {
            case PUSH -> push.send(claimed.event());
            case ERP -> erp.publish(claimed.event());
        }
    }

    private void failed(ClaimedEvent claimed, String error, boolean retryable) {
        int attempts = claimed.attempts() + 1;
        if (!retryable || attempts >= maxAttempts) {
            dead(claimed, attempts, error);
            return;
        }
        Instant nextAttemptAt = clock.instant().plus(backoff.delayAfter(attempts));
        if (repository.scheduleRetry(claimed, attempts, nextAttemptAt, error)) {
            LOG.warn("Outbox event {} to {} for order {} failed attempt {} of {}, retrying at {}: {}",
                    eventId(claimed), claimed.destination(), orderNumber(claimed), attempts, maxAttempts,
                    nextAttemptAt, error);
        } else {
            leaseLost(claimed);
        }
    }

    private void dead(ClaimedEvent claimed, int attempts, String error) {
        if (repository.markDead(claimed, attempts, error)) {
            LOG.error("Outbox event {} to {} is DEAD for order {} after {} attempts, needs an operator: {}",
                    eventId(claimed), claimed.destination(), orderNumber(claimed), attempts, error);
        } else {
            leaseLost(claimed);
        }
    }

    /** The lease expired during the call and another poll took the event over: its outcome is the one that counts. */
    private void leaseLost(ClaimedEvent claimed) {
        LOG.warn("Outbox event {} to {} for order {} was taken over after its lease expired, outcome not recorded",
                eventId(claimed), claimed.destination(), orderNumber(claimed));
    }

    private static UUID eventId(ClaimedEvent claimed) {
        return claimed.event().eventId();
    }

    private static String orderNumber(ClaimedEvent claimed) {
        return claimed.event().orderNumber();
    }
}
