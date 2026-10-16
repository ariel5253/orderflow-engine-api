package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.Money;

/**
 * How a country taxes gift lines (SPEC BR-04), the {@code gift_tax_policy} of the {@code countries}
 * table. The policy decides only the taxable base of a gift line. The tax is then taken on that base
 * as on any other line, at the gift SKU's rate and rounded once for the line (RP-4), so the tax of a
 * taxed gift is paid by the customer and is part of the total.
 */
public enum GiftTaxPolicy {

    /** A gift is not taxed: its taxable base, and so its tax, is zero. */
    EXEMPT {
        @Override
        public Money taxableBase(Money referenceValue) {
            return Money.zero(referenceValue.policy());
        }
    },

    /** A gift is taxed on its reference value, what its units list at. */
    TAXED {
        @Override
        public Money taxableBase(Money referenceValue) {
            return referenceValue;
        }
    };

    /** The taxable base of a gift line whose units list at the given reference value. */
    public abstract Money taxableBase(Money referenceValue);
}
