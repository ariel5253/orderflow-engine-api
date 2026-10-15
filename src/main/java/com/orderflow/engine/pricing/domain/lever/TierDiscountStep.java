package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Discount;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Step 3 of SPEC BR-01: the tier discounts of BR-02. A SKU with its own SKU rule gets that rule
 * only, and is neither counted nor discounted by the rule of its family (A-02). The other SKUs of a
 * family share one count, filled in SKU order (A-03), so each line's units take the next positions
 * of its family's brackets. Each line's discount is computed exactly and rounded once for the line
 * (RF-1), never once per bracket, and a discount that rounds to zero is not emitted (RP-2).
 */
public final class TierDiscountStep {

    private TierDiscountStep() {
    }

    /**
     * Returns the tier discount of each SKU that earns one, keyed by SKU code. The step counts only
     * the units it is given: the paid units that no combo consumed. Units a combo consumes, and the
     * units of a gift line, never reach a tier threshold and never get a tier discount (A-04).
     */
    public static Map<String, Discount> apply(List<TierRule> rules, List<Units> units) {
        Map<String, TierRule> skuRules = rulesOf(rules, Scope.SKU);
        Map<String, TierRule> familyRules = rulesOf(rules, Scope.FAMILY);
        Map<String, Long> countedByFamily = new HashMap<>();
        Map<String, Discount> discounts = new HashMap<>();
        for (Units line : units.stream().sorted(Comparator.comparing(each -> each.product().sku())).toList()) {
            Product product = line.product();
            TierRule skuRule = skuRules.get(product.sku());
            TierRule familyRule = familyRules.get(product.family());
            if (skuRule != null) {
                discount(skuRule, 1, line.count(), product, discounts);
            } else if (familyRule != null) {
                long counted = countedByFamily.getOrDefault(product.family(), 0L);
                long last = Math.addExact(counted, line.count());
                discount(familyRule, counted + 1, last, product, discounts);
                countedByFamily.put(product.family(), last);
            }
        }
        return Map.copyOf(discounts);
    }

    private static Map<String, TierRule> rulesOf(List<TierRule> rules, Scope scope) {
        Map<String, TierRule> byTarget = new HashMap<>();
        rules.stream().filter(rule -> rule.scope() == scope).forEach(rule -> byTarget.put(rule.target(), rule));
        return byTarget;
    }

    private static void discount(TierRule rule, long first, long last, Product product,
            Map<String, Discount> discounts) {
        Money unitPrice = product.listPrice();
        CurrencyPolicy policy = unitPrice.policy();
        Money amount = new Money(policy.round(rule.exactDiscount(first, last, unitPrice.amount())), policy);
        if (amount.amount().signum() > 0) {
            discounts.put(product.sku(), new Discount(Discount.Kind.TIER, rule.id(), amount));
        }
    }

    /** The units of one paid line that can earn a tier discount, and the product they belong to. */
    public record Units(Product product, long count) {

        public Units {
            Objects.requireNonNull(product, "product");
            if (count < 0) {
                throw new IllegalArgumentException(
                        "SKU " + product.sku() + " cannot have a negative count of units: " + count);
            }
        }
    }
}
