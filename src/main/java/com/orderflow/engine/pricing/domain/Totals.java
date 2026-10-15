package com.orderflow.engine.pricing.domain;

import java.util.List;

/**
 * The totals of a breakdown, as the API shows them. Each amount is a sum of rounded line values and
 * is never rounded again (RP-5), so a total always equals the sum of its lines. The discounts hold
 * one entry per origin, the sum of that origin's line components, sorted by origin.
 */
public record Totals(
        Money grossSubtotal,
        List<Discount> discounts,
        Money taxableBase,
        Money taxes,
        Money total) {

    public Totals {
        discounts = List.copyOf(discounts);
    }
}
