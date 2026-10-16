package com.orderflow.engine.ordering;

import com.orderflow.engine.ordering.OrderRejectedException.Code;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteStatus;
import com.orderflow.engine.quoting.QuoteView;
import com.orderflow.engine.quoting.SnapshotProvider;
import com.orderflow.engine.quoting.StoredQuote;
import com.orderflow.engine.quoting.TenantPolicy;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a customer's order history (api-contract E-05): the newest orders first, in the tenant's currency. Each total
 * is rendered from the order's sealed quote exactly as the order itself renders it (E-04), so the history and the order
 * can never show different totals (BD-04). A customer reads only their own history: a history asked for any other
 * customer, or for a customer the tenant does not have, is not found, with the same answer in both cases, so the
 * history of someone else is never revealed (BD-01).
 *
 * <p>The history is read from the primary tables the confirmation commits to, under READ COMMITTED, so every order
 * whose 201 was sent is listed (I-201). The transaction is deliberately not read-only: a read-only transaction is what
 * a routing data source sends to a replica, and order reads stay on the primary (data model section 9).
 */
public class ListOrdersUseCase {

    private final SnapshotProvider snapshots;
    private final OrderRepository orders;
    private final QuoteRepository quotes;

    public ListOrdersUseCase(SnapshotProvider snapshots, OrderRepository orders, QuoteRepository quotes) {
        this.snapshots = snapshots;
        this.orders = orders;
        this.quotes = quotes;
    }

    /**
     * The newest orders of {@code customerId}, at most {@code limit} of them, as the caller {@code callerId} reads
     * them.
     *
     * @throws OrderRejectedException when the tenant does not exist, or the history is not the caller's own
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderHistory list(String tenantId, UUID callerId, UUID customerId, int limit) {
        TenantPolicy tenant = snapshots.tenantPolicy(tenantId).orElseThrow(() -> new OrderRejectedException(
                Code.TENANT_NOT_FOUND, "Tenant '" + tenantId + "' does not exist"));
        if (!customerId.equals(callerId)) {
            throw new OrderRejectedException(Code.CUSTOMER_NOT_FOUND,
                    "Customer " + callerId + " of tenant '" + tenantId + "' asked for the history of " + customerId);
        }
        if (!snapshots.customerExists(tenantId, customerId)) {
            throw new OrderRejectedException(Code.CUSTOMER_NOT_FOUND,
                    "Customer " + customerId + " does not exist for tenant '" + tenantId + "'");
        }
        List<OrderSummary> history = orders.history(tenantId, customerId, limit).stream()
                .map(order -> new OrderSummary(order.orderId(), order.orderNumber(), order.confirmedAt(),
                        total(tenantId, customerId, order)))
                .toList();
        return new OrderHistory(tenant.currency().currencyCode(), history);
    }

    /** The total of the order, rendered from its sealed quote as the order's own body renders it. */
    private Money total(String tenantId, UUID customerId, StoredOrder order) {
        // The order's foreign key pins its quote and the quote's customer, so the quote is always there.
        StoredQuote quote = quotes.find(tenantId, customerId, order.quoteId()).orElseThrow();
        return QuoteView.render(quote, QuoteStatus.CONFIRMED).totals().total();
    }
}
