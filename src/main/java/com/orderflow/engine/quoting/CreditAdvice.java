package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.Money;
import java.util.Objects;

/**
 * The advisory credit check of SPEC BR-06 for one quote total: the credit the customer has available, whether the total
 * fits in it, and by how much it falls short. It reserves nothing (A-08): the limit is enforced only when the order is
 * confirmed.
 */
public record CreditAdvice(boolean fits, Money available, Money shortfall) {

    public CreditAdvice {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(shortfall, "shortfall");
    }

    /**
     * {@code available = limit - consumed}, {@code fits = total <= available}, so a total equal to the available
     * credit fits, and {@code shortfall = max(0, total - available)}.
     */
    public static CreditAdvice of(Money total, Money limit, Money consumed) {
        Money available = limit.minus(consumed);
        boolean fits = total.compareTo(available) <= 0;
        Money shortfall = fits ? Money.zero(total.policy()) : total.minus(available);
        return new CreditAdvice(fits, available, shortfall);
    }
}
