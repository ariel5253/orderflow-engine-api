package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.CartNormalizer;
import com.orderflow.engine.pricing.domain.CartRejectedException;
import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Gift;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.Trigger;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep.Units;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Step 1 of SPEC BR-01: the gifts of BR-04. A gift is triggered by what the customer buys, so every
 * rule is evaluated on the paid units of the normalized cart and on their gross subtotal, before a
 * combo consumes a unit or a discount lowers a price (A-10). Gift units are never paid units, so a
 * gift never counts toward a condition. A rule that applies grants its gift quantity once for each
 * time it applies: every whole multiple of its condition when it is repeatable, once otherwise. A
 * derived gift quantity above the bound of BR-00 rejects the cart, as a line quantity above it does.
 */
public final class GiftStep {

    // Ids are compared as text, which is how PostgreSQL orders uuid values, byte by byte, as the combo
    // step does. Gifts do not depend on each other, so the order only keeps the gift lines stable.
    private static final Comparator<GiftRule> ID_ORDER =
            Comparator.comparing(rule -> rule.id().toString());

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private GiftStep() {
    }

    /**
     * Returns the gifts that the paid units earn, one per rule that applies, in rule-id order. The
     * paid units hold one entry per SKU, as in a normalized cart.
     */
    public static List<Gift> apply(List<GiftRule> rules, List<Units> paid) {
        List<Gift> gifts = new ArrayList<>();
        for (GiftRule rule : rules.stream().sorted(ID_ORDER).toList()) {
            long multiples = multiples(rule.trigger(), paid);
            long applications = rule.repeatable() ? multiples : Math.min(multiples, 1L);
            if (applications > 0) {
                gifts.add(new Gift(rule.id(), rule.giftSku(), giftQty(rule, applications)));
            }
        }
        return List.copyOf(gifts);
    }

    /**
     * The units a rule gives for its applications, at most the bound of SPEC BR-00, or the cart is rejected. The
     * bound is checked before the product is taken: with integer division, {@code applications * giftQty <= bound}
     * exactly when {@code applications <= bound / giftQty}, because giftQty is at least one. The product is then within
     * the bound, so it never overflows or wraps around.
     */
    private static long giftQty(GiftRule rule, long applications) {
        if (applications > CartNormalizer.MAX_QTY / rule.giftQty()) {
            throw new CartRejectedException(Code.INVALID_QUANTITY, "Gift rule " + rule.id() + " derives more than "
                    + CartNormalizer.MAX_QTY + " units of SKU '" + rule.giftSku() + "'");
        }
        return applications * rule.giftQty();
    }

    // How many whole times the paid units meet the condition.
    private static long multiples(Trigger trigger, List<Units> paid) {
        return switch (trigger) {
            case SkuQuantity sku -> unitsOf(paid, product -> product.sku().equals(sku.sku())) / sku.minQty();
            case FamilyQuantity family ->
                    unitsOf(paid, product -> product.family().equals(family.family())) / family.minQty();
            // Both amounts are held at the currency scale, so dividing their minor units gives the
            // whole multiples exactly, with no rounding.
            case GrossSubtotal gross -> saturated(grossSubtotal(paid, gross.minGross().policy()).amount()
                    .unscaledValue()
                    .divide(gross.minGross().amount().unscaledValue()));
        };
    }

    // A gross subtotal can hold more multiples than a long, for example a large price over a small minimum. Any count
    // from Long.MAX_VALUE on derives a gift above the bound for a rule that repeats, and one application for a rule
    // that does not, so the count is held there instead of failing.
    private static long saturated(BigInteger multiples) {
        return multiples.min(LONG_MAX).longValueExact();
    }

    private static long unitsOf(List<Units> paid, Predicate<Product> counted) {
        return paid.stream()
                .filter(units -> counted.test(units.product()))
                .mapToLong(Units::count)
                .reduce(0L, Math::addExact);
    }

    // The gross subtotal before discounts: every paid unit at its list price.
    private static Money grossSubtotal(List<Units> paid, CurrencyPolicy policy) {
        return paid.stream()
                .map(units -> units.product().listPrice().times(units.count()))
                .reduce(Money.zero(policy), Money::plus);
    }
}
