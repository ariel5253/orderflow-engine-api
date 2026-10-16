package com.orderflow.engine.ordering;

import com.orderflow.engine.ordering.OrderRejectedException.Code;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteStatus;
import com.orderflow.engine.quoting.QuoteView;
import com.orderflow.engine.quoting.SnapshotProvider;
import com.orderflow.engine.quoting.StoredQuote;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one order (api-contract E-04) with the body of its confirmation. The body is rendered again from the order row
 * and its sealed quote, which are never updated, so it shows the figures the 201 showed (BD-04). An order of another
 * tenant or customer is not found, and neither is any order asked for by a customer the tenant does not have, so the
 * answer never reveals whether an order or a customer exists (BD-01).
 *
 * <p>The order is read from the primary tables the confirmation commits to, under READ COMMITTED, so an order is
 * readable as soon as its 201 is sent (I-201). The transaction is deliberately not read-only: a read-only transaction
 * is what a routing data source sends to a replica, and order reads stay on the primary (data model section 9).
 */
public class GetOrderUseCase {

    private final SnapshotProvider snapshots;
    private final OrderRepository orders;
    private final QuoteRepository quotes;

    public GetOrderUseCase(SnapshotProvider snapshots, OrderRepository orders, QuoteRepository quotes) {
        this.snapshots = snapshots;
        this.orders = orders;
        this.quotes = quotes;
    }

    /** @throws OrderRejectedException when the tenant does not exist, or the caller has no such order */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView get(String tenantId, UUID customerId, UUID orderId) {
        if (snapshots.tenantPolicy(tenantId).isEmpty()) {
            throw new OrderRejectedException(Code.TENANT_NOT_FOUND, "Tenant '" + tenantId + "' does not exist");
        }
        StoredOrder order = orders.find(tenantId, customerId, orderId).orElseThrow(() -> new OrderRejectedException(
                Code.ORDER_NOT_FOUND,
                "Order " + orderId + " does not belong to customer " + customerId + " of tenant '" + tenantId + "'"));
        // The order's foreign key pins its quote and the quote's customer, so the quote is always there.
        StoredQuote quote = quotes.find(tenantId, customerId, order.quoteId()).orElseThrow();
        return new OrderView(order.orderId(), order.orderNumber(), order.quoteId(), order.confirmedAt(),
                quote.sealed().paymentMethod(), QuoteView.render(quote, QuoteStatus.CONFIRMED));
    }
}
