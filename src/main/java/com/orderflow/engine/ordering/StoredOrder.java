package com.orderflow.engine.ordering;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An order as it is read back: what identifies it, the sealed quote it confirms and when it was confirmed. It holds no
 * amount, because every figure of an order is in its quote (BD-04).
 */
public record StoredOrder(UUID orderId, String orderNumber, UUID quoteId, Instant confirmedAt) {

    public StoredOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
    }
}
