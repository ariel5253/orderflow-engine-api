package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.ordering.OrderHistory;
import com.orderflow.engine.ordering.OrderSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The body of an order history (api-contract E-05), with its fields in the order of the contract: the ISO 4217 code of
 * the tenant's currency, given once for every total and also when there is no order, then the orders newest first.
 * A total is a JSON string at the currency scale, never a JSON number.
 */
public record OrderHistoryResponse(String currency, List<Item> items) {

    public static OrderHistoryResponse from(OrderHistory history) {
        return new OrderHistoryResponse(history.currency(), history.orders().stream().map(Item::from).toList());
    }

    /** One order of the history. */
    public record Item(UUID orderId, String orderNumber, Instant confirmedAt, String total) {

        static Item from(OrderSummary order) {
            return new Item(order.orderId(), order.orderNumber(), order.confirmedAt(),
                    QuoteResponse.money(order.total()));
        }
    }
}
