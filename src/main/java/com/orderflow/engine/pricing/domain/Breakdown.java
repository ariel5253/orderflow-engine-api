package com.orderflow.engine.pricing.domain;

import java.util.List;

/**
 * The result of pricing a cart: the ISO currency code, one line per normalized cart line in SKU
 * order followed by one gift line per gift granted, in gift-rule order, and the totals.
 */
public record Breakdown(String currency, List<LineBreakdown> lines, Totals totals) {

    public Breakdown {
        lines = List.copyOf(lines);
    }
}
