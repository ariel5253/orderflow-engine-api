package com.orderflow.engine.pricing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * The priced form of one normalized cart line, as the API shows it. The gross is exact (RP-1). Each
 * discount component is rounded once for the line (RP-2). The net and the taxable base are exact
 * (RP-3), and the tax is rounded once for the line (RP-4).
 */
public record LineBreakdown(
        String sku,
        long qty,
        Money unitPrice,
        Money gross,
        List<Discount> discounts,
        Money net,
        Money taxableBase,
        BigDecimal taxRate,
        Money tax) {

    public LineBreakdown {
        discounts = List.copyOf(discounts);
    }
}
