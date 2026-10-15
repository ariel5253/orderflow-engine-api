package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.ordering.OrderView;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteStatus;
import com.orderflow.engine.quoting.QuoteView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The body of a confirmed order (api-contract E-03), with its fields in the order of the contract. The lines and totals
 * have the shape of a quote's, because they are rendered from the quote the order points at (BD-04).
 */
public record OrderResponse(
        UUID orderId,
        String orderNumber,
        QuoteStatus status,
        UUID quoteId,
        Instant confirmedAt,
        String currency,
        List<QuoteResponse.Line> lines,
        QuoteResponse.Totals totals,
        PaymentMethod paymentMethod) {

    public static OrderResponse from(OrderView order) {
        QuoteView quote = order.quote();
        return new OrderResponse(order.orderId(), order.orderNumber(), quote.status(), order.quoteId(),
                order.confirmedAt(), quote.currency(), quote.lines().stream().map(QuoteResponse.Line::from).toList(),
                QuoteResponse.Totals.from(quote.totals()), order.paymentMethod());
    }
}
