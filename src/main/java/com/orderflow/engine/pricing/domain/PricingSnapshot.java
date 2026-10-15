package com.orderflow.engine.pricing.domain;

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
 * Everything the engine reads to price a cart at one moment: the currency policy of the tenant's
 * country, the products the tenant sells, with their family, list price and tax rate at that
 * moment, and the active tier rules. The engine reads nothing else, so the same cart and snapshot
 * always give the same breakdown. A SKU that is missing from the snapshot is unknown to the tenant,
 * whether no tenant sells it or another tenant does.
 */
public final class PricingSnapshot {

    private final CurrencyPolicy policy;
    private final Map<String, Product> productsBySku;
    private final List<TierRule> tierRules;

    public PricingSnapshot(CurrencyPolicy policy, List<Product> products, List<TierRule> tierRules) {
        this.policy = Objects.requireNonNull(policy, "policy");
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

    public CurrencyPolicy policy() {
        return policy;
    }

    /** The product sold under this SKU code, or empty when the tenant does not sell it. */
    public Optional<Product> product(String sku) {
        return Optional.ofNullable(productsBySku.get(sku));
    }

    /** The active tier rules of the tenant, at most one per SKU and one per family. */
    public List<TierRule> tierRules() {
        return tierRules;
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
