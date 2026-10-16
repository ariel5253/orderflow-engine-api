package com.orderflow.engine.ordering;

import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A confirmed order in the shape of api-contract E-03: what identifies the order, and the breakdown rendered from its
 * sealed quote, which holds every amount (BD-04). The quote is rendered in status CONFIRMED.
 */
public record OrderView(
        UUID orderId,
        String orderNumber,
        UUID quoteId,
        Instant confirmedAt,
        PaymentMethod paymentMethod,
        QuoteView quote) {

    public OrderView {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        Objects.requireNonNull(quote, "quote");
    }

    /**
     * Turns a confirmed order into the exact bytes of the response that announces it. The confirmation stores these
     * bytes under its idempotency key, so a replay returns what the first request received, byte for byte.
     */
    @FunctionalInterface
    public interface Encoder {

        byte[] encode(OrderView order);
    }
}
