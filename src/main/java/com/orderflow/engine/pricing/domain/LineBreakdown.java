package com.orderflow.engine.pricing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The priced form of one line, as the API shows it: a paid line of the normalized cart, or a gift
 * line. The gross is exact (RP-1). Each discount component, the tier discount and each combo
 * allocation, is rounded once for the line (RP-2). The combo allocations are not discount entries:
 * they only take the line's part of each combo out of its taxable base (A-07). The net and the
 * taxable base are exact (RP-3), and the tax is rounded once for the line (RP-4).
 *
 * <p>A gift line carries its {@link #gift()} (BR-04). Its unit price, gross and net are zero and it
 * has no discount component, so it adds nothing to what the customer pays for product. Its taxable
 * base is not its net: the country's gift tax policy sets it to zero or to the reference value.
 */
public record LineBreakdown(
        String sku,
        long qty,
        Optional<GiftReference> gift,
        Money unitPrice,
        Money gross,
        List<Discount> discounts,
        List<ComboAllocation> comboAllocations,
        Money net,
        Money taxableBase,
        BigDecimal taxRate,
        Money tax) {

    public LineBreakdown {
        Objects.requireNonNull(gift, "gift");
        discounts = List.copyOf(discounts);
        comboAllocations = List.copyOf(comboAllocations);
    }

    public boolean isGift() {
        return gift.isPresent();
    }

    /**
     * What only a gift line carries: the rule that granted it, and its reference value, what its
     * units list at (BR-04).
     */
    public record GiftReference(UUID ruleId, Money referenceValue) {

        public GiftReference {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(referenceValue, "referenceValue");
        }

        /** The origin as the API shows it, {@code GIFT:<ruleId>}, the same as the gift's own. */
        public String origin() {
            return Gift.origin(ruleId);
        }
    }
}
