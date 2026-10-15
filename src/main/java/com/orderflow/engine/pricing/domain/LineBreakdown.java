package com.orderflow.engine.pricing.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * The priced form of one normalized cart line, as the API shows it. The gross is exact (RP-1). Each
 * discount component, the tier discount and each combo allocation, is rounded once for the line
 * (RP-2). The combo allocations are not discount entries: they only take the line's part of each
 * combo out of its taxable base (A-07). The net and the taxable base are exact (RP-3), and the tax
 * is rounded once for the line (RP-4).
 */
public record LineBreakdown(
        String sku,
        long qty,
        Money unitPrice,
        Money gross,
        List<Discount> discounts,
        List<ComboAllocation> comboAllocations,
        Money net,
        Money taxableBase,
        BigDecimal taxRate,
        Money tax) {

    public LineBreakdown {
        discounts = List.copyOf(discounts);
        comboAllocations = List.copyOf(comboAllocations);
    }
}
