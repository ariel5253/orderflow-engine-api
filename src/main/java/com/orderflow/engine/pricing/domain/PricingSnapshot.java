package com.orderflow.engine.pricing.domain;

import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the engine reads to price a cart at one moment: the currency policy and the gift tax
 * policy of the tenant's country, the products the tenant sells, with their family, list price and
 * tax rate at that moment, and the active tier, combo and gift rules. The engine reads nothing else,
 * so the same cart and snapshot always give the same breakdown. A SKU that is missing from the
 * snapshot is unknown to the tenant, whether no tenant sells it or another tenant does.
 */
public final class PricingSnapshot {

    private final CurrencyPolicy policy;
    private final GiftTaxPolicy giftTaxPolicy;
    private final Map<String, Product> productsBySku;
    private final List<TierRule> tierRules;
    private final List<ComboRule> comboRules;
    private final List<GiftRule> giftRules;

    public PricingSnapshot(CurrencyPolicy policy, GiftTaxPolicy giftTaxPolicy, List<Product> products,
            List<TierRule> tierRules, List<ComboRule> comboRules, List<GiftRule> giftRules) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.giftTaxPolicy = Objects.requireNonNull(giftTaxPolicy, "giftTaxPolicy");
        Map<String, Product> bySku = new HashMap<>();
        for (Product product : products) {
            if (!product.listPrice().policy().equals(policy)) {
                throw new IllegalArgumentException("Product " + product.sku() + " is priced with "
                        + product.listPrice().policy() + ", not with " + policy);
            }
            if (bySku.putIfAbsent(product.sku(), product) != null) {
                throw new IllegalArgumentException("Product " + product.sku() + " is listed twice");
            }
        }
        this.productsBySku = Map.copyOf(bySku);
        requireDistinctRules(tierRules);
        this.tierRules = List.copyOf(tierRules);
        requireDistinctCombos(comboRules, policy);
        this.comboRules = List.copyOf(comboRules);
        requireValidGifts(giftRules, policy, productsBySku);
        this.giftRules = List.copyOf(giftRules);
    }

    // The database keys each rule by its id and keeps one active rule per target (BR-02), so a
    // repeated id or target is a broken snapshot, not a choice the engine could make.
    private static void requireDistinctRules(List<TierRule> tierRules) {
        Set<UUID> ids = new HashSet<>();
        Set<Map.Entry<TierRule.Scope, String>> targets = new HashSet<>();
        for (TierRule rule : tierRules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Tier rule " + rule.id() + " is listed twice");
            }
            if (!targets.add(Map.entry(rule.scope(), rule.target()))) {
                throw new IllegalArgumentException("Two active tier rules target "
                        + rule.scope() + " " + rule.target());
            }
        }
    }

    // The database keys each combo by its id, and a combo is priced in the tenant's currency.
    private static void requireDistinctCombos(List<ComboRule> comboRules, CurrencyPolicy policy) {
        Set<UUID> ids = new HashSet<>();
        for (ComboRule rule : comboRules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Combo rule " + rule.id() + " is listed twice");
            }
            if (!rule.comboPrice().policy().equals(policy)) {
                throw new IllegalArgumentException("Combo rule " + rule.id() + " is priced with "
                        + rule.comboPrice().policy() + ", not with " + policy);
            }
        }
    }

    // The database keys each gift rule by its id and points it at a product of the tenant, whose list
    // price and tax rate price the gift line. A gross subtotal is in the tenant's currency.
    private static void requireValidGifts(List<GiftRule> giftRules, CurrencyPolicy policy,
            Map<String, Product> productsBySku) {
        Set<UUID> ids = new HashSet<>();
        for (GiftRule rule : giftRules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Gift rule " + rule.id() + " is listed twice");
            }
            if (!productsBySku.containsKey(rule.giftSku())) {
                throw new IllegalArgumentException("Gift rule " + rule.id() + " gives SKU "
                        + rule.giftSku() + ", which this tenant does not sell");
            }
            if (rule.trigger() instanceof GrossSubtotal gross && !gross.minGross().policy().equals(policy)) {
                throw new IllegalArgumentException("Gift rule " + rule.id() + " sets its gross subtotal with "
                        + gross.minGross().policy() + ", not with " + policy);
            }
        }
    }

    public CurrencyPolicy policy() {
        return policy;
    }

    /** How the tenant's country taxes gift lines (BR-04). */
    public GiftTaxPolicy giftTaxPolicy() {
        return giftTaxPolicy;
    }

    /** The product sold under this SKU code, or empty when the tenant does not sell it. */
    public Optional<Product> product(String sku) {
        return Optional.ofNullable(productsBySku.get(sku));
    }

    /** The active tier rules of the tenant, at most one per SKU and one per family. */
    public List<TierRule> tierRules() {
        return tierRules;
    }

    /** The active combo rules of the tenant, in the order they were given. */
    public List<ComboRule> comboRules() {
        return comboRules;
    }

    /** The active gift rules of the tenant, in the order they were given. Each gift SKU is sold here. */
    public List<GiftRule> giftRules() {
        return giftRules;
    }

    /**
     * A product the tenant sells. The family is the code of the group a family tier rule targets.
     * The list price is tax-exclusive, and the tax rate is a fraction between 0 and 1, as in the
     * {@code products} table.
     */
    public record Product(String sku, String family, Money listPrice, BigDecimal taxRate) {

        public Product {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(listPrice, "listPrice");
            Objects.requireNonNull(taxRate, "taxRate");
            if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "Tax rate of product " + sku + " must be between 0 and 1: " + taxRate);
            }
        }
    }
}
