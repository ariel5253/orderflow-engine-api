package com.orderflow.engine.pricing.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The part of a combo's discount that one participating line carries (SPEC BR-03). It exists only
 * to derive the line's taxable base and is not a discount entry: the combo shows as one
 * {@code COMBO:<id>} discount in the totals (A-07). The amount is already rounded to the currency
 * scale (RP-2) and is always positive: a part that rounds to zero is never emitted.
 */
public record ComboAllocation(UUID comboId, Money amount) {

    public ComboAllocation {
        Objects.requireNonNull(comboId, "comboId");
        Objects.requireNonNull(amount, "amount");
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "A combo allocation must be positive: " + comboId + " " + amount.amount());
        }
    }
}
