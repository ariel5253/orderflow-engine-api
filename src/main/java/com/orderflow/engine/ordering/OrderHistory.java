package com.orderflow.engine.ordering;

import java.util.List;
import java.util.Objects;

/**
 * A customer's order history (api-contract E-05): the orders newest first, and the ISO 4217 code of the tenant's
 * currency, which every total is in. The code is known even when there is no order, because it is the tenant's.
 */
public record OrderHistory(String currency, List<OrderSummary> orders) {

    public OrderHistory {
        Objects.requireNonNull(currency, "currency");
        orders = List.copyOf(orders);
    }
}
