package com.orderflow.engine.pricing.domain;

import java.util.List;

/**
 * The totals of a breakdown, as the API shows them. Each amount is a sum of rounded line values and
 * is never rounded again (RP-5), so a total always equals the sum of its lines. The discounts hold
 * one entry per origin, the sum of that origin's line components, sorted by origin. The gifts hold
 * one entry per gift line, in the order of the lines: a gift is not a discount, so it never appears
 * among the discounts.
 */
public record Totals(
        Money grossSubtotal,
        List<Discount> discounts,
        List<Gift> gifts,
        Money taxableBase,
        Money taxes,
        Money total) {

    public Totals {
        discounts = List.copyOf(discounts);
        gifts = List.copyOf(gifts);
    }
}
