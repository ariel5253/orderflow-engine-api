package com.orderflow.engine.ordering;

import com.orderflow.engine.pricing.domain.Money;
import java.util.UUID;

/**
 * The customer's credit account and its append-only ledger (SPEC BR-06). The consumed amount is the sum of the ledger,
 * and both change in the order transaction, so a credit order consumes credit exactly once.
 */
public interface CreditLedger {

    /**
     * Adds the amount to what the customer has consumed, only if the result stays within the limit, in one conditional
     * update. While a concurrent confirmation holds the account, this waits for it to end, then checks the limit
     * against what that confirmation consumed.
     *
     * @return false when the amount does not fit in the available credit, or the customer has no credit account
     * @throws OrderRejectedException with {@code IDEMPOTENCY_KEY_IN_PROGRESS} when the wait passes the lock timeout
     */
    boolean consume(String tenantId, UUID customerId, Money amount);

    /** Appends the credit movement of an order, which must be inserted first. */
    void record(String tenantId, UUID orderId, Money amount);
}
