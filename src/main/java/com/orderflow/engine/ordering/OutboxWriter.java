package com.orderflow.engine.ordering;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the external effects of a confirmation in the order transaction (SPEC section 7.4, P-04). Nothing is sent
 * here: the outbox relay delivers the events after the commit, so an effect never describes an order that does not
 * exist, and is never lost when it does.
 */
public interface OutboxWriter {

    /**
     * Records that the order was confirmed, as one pending event for push and one for the ERP, both due at once. Each
     * event has an id of its own, which consumers deduplicate on.
     */
    void orderConfirmed(String tenantId, UUID orderId, Instant confirmedAt);
}
