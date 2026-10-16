package com.orderflow.engine.ordering;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where confirmed orders live. An order stores no amount: it holds its number and points at its sealed quote, which
 * holds every figure of its breakdown (SPEC section 6, BD-04). The quote is locked through this port as well, because
 * locking it is how a confirmation claims it.
 */
public interface OrderRepository {

    /**
     * Bounds every lock wait of the current transaction. A statement that waits longer for a lock held by a concurrent
     * confirmation fails with {@code IDEMPOTENCY_KEY_IN_PROGRESS} instead of hanging (data model section 8).
     */
    void limitLockWaits(Duration timeout);

    /**
     * Locks the quote for the rest of the current transaction, when it belongs to this tenant and this customer. While
     * a concurrent confirmation holds the quote, this waits for it to end.
     *
     * @return false when the caller has no such quote
     * @throws OrderRejectedException with {@code IDEMPOTENCY_KEY_IN_PROGRESS} when the wait passes the lock timeout
     */
    boolean lockQuote(String tenantId, UUID customerId, UUID quoteId);

    /**
     * The order that confirms the quote, if any. Read once the quote is locked, it also sees an order that a
     * concurrent confirmation committed while this one waited for the lock.
     */
    Optional<PlacedOrder> findByQuote(String tenantId, UUID quoteId);

    /** Inserts the order of the quote with the next number of the tenant's country. A rollback leaves a gap. */
    PlacedOrder insert(String tenantId, UUID customerId, UUID quoteId, Instant confirmedAt);

    /**
     * The order with this id, only when it belongs to this tenant and this customer. An order of anyone else is not
     * found, so its existence is never revealed (BD-01).
     */
    Optional<StoredOrder> find(String tenantId, UUID customerId, UUID orderId);

    /**
     * The newest orders of the customer, at most {@code limit} of them, newest first. Orders confirmed at the same
     * instant are listed by their ids, the greater first, so a history always lists them the same way.
     */
    List<StoredOrder> history(String tenantId, UUID customerId, int limit);
}
