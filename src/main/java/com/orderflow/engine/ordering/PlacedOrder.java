package com.orderflow.engine.ordering;

import java.util.Objects;
import java.util.UUID;

/**
 * An order as a client finds it: its id and its human-readable number, {@code <COUNTRY>-<sequence>} (api-contract
 * section 1). It is what a confirmation creates, and what a later confirmation of the same quote is told about.
 */
public record PlacedOrder(UUID orderId, String orderNumber) {

    public PlacedOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderNumber, "orderNumber");
    }
}
