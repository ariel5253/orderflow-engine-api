package com.orderflow.engine.ordering;

import com.orderflow.engine.pricing.domain.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One order of a customer's history (api-contract E-05): what identifies it, when it was confirmed, and its total,
 * which is the total of its sealed quote (BD-04).
 */
public record OrderSummary(UUID orderId, String orderNumber, Instant confirmedAt, Money total) {

    public OrderSummary {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(total, "total");
    }
}
