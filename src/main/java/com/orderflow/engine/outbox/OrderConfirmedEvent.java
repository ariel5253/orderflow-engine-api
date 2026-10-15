package com.orderflow.engine.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * What push and the ERP receive for a confirmed order. It is built at send time from the order row, which is never
 * updated, so every re-send carries the same content. {@code eventId} is the outbox row id: it is the downstream
 * idempotency key that consumers deduplicate on.
 */
public record OrderConfirmedEvent(
        UUID eventId,
        String tenantId,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        UUID quoteId,
        Instant confirmedAt) {
}
