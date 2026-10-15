package com.orderflow.engine.outbox;

import java.time.Instant;
import java.util.List;

/**
 * The outbox rows the relay works on. Every operation is a single statement that commits on its own, so no database
 * transaction, and no row lock, is ever open while the relay calls a gateway (SPEC §7.4, RF-8).
 */
public interface OutboxRepository {

    /**
     * Leases up to {@code limit} due events, oldest first: PENDING, {@code next_attempt_at} reached, and not leased.
     * The lease lasts until {@code leaseUntil}. Rows another relay is claiming at the same moment are skipped, not
     * waited for. An event whose lease expired with no outcome recorded, after a crash between send and mark, is due
     * again.
     */
    List<ClaimedEvent> claimDue(Instant now, Instant leaseUntil, int limit);

    /** Marks the event SENT and releases its lease. Returns false when the lease was lost, so nothing changed. */
    boolean markSent(ClaimedEvent claimed);

    /**
     * Records a failed attempt: stores {@code attempts}, releases the lease and sets the next attempt. Returns false
     * when the lease was lost, so nothing changed.
     */
    boolean scheduleRetry(ClaimedEvent claimed, int attempts, Instant nextAttemptAt, String error);

    /**
     * Marks the event DEAD, so it is never retried again, and releases its lease. Returns false when the lease was
     * lost, so nothing changed.
     */
    boolean markDead(ClaimedEvent claimed, int attempts, String error);

    /** Events still to be delivered, leased or not: the backlog. */
    long countPending();

    /** Events that are no longer retried and wait for an operator. */
    long countDead();
}
