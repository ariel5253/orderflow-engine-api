package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks a quote total against the customer's credit account as it stands now (SPEC BR-06). Credit is not a price
 * lever, so the check stays outside the pure engine (BR-01, step 8). It is advisory: nothing is reserved (A-08).
 */
public interface CreditAdvisor {

    /** The advice for this total, or empty when the customer has no credit account. */
    Optional<CreditAdvice> advise(String tenantId, UUID customerId, Money total);
}
