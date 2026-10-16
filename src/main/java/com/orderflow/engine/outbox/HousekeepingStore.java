package com.orderflow.engine.outbox;

import java.time.Instant;

/**
 * The rows the housekeeping job purges. Each purge covers every tenant, commits on its own and returns how many rows
 * it removed.
 */
public interface HousekeepingStore {

    /**
     * Removes the idempotency keys created before {@code cutoff}. Past its retention a key no longer replays its first
     * response. Its order stays, so a retry on the same quote is still answered with QUOTE_ALREADY_CONFIRMED and the
     * order number.
     */
    int purgeIdempotencyKeys(Instant cutoff);

    /**
     * Removes the quotes that expired before {@code cutoff} and were never confirmed, with their lines and discount
     * components. A confirmed quote holds its order's lines, so it is never removed.
     */
    int purgeUnconfirmedQuotes(Instant cutoff);

    /**
     * Removes the SENT outbox events created before {@code cutoff}. A DEAD event waits for an operator to requeue it,
     * and a PENDING one is still due, so neither is removed.
     */
    int purgeSentEvents(Instant cutoff);
}
