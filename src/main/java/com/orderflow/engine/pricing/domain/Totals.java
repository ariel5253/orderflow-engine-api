package com.orderflow.engine.pricing.domain;

/**
 * The totals of a breakdown, as the API shows them. Each one is a sum of rounded line values and is
 * never rounded again (RP-5), so a total always equals the sum of its lines.
 */
public record Totals(Money grossSubtotal, Money taxableBase, Money taxes, Money total) {
}
