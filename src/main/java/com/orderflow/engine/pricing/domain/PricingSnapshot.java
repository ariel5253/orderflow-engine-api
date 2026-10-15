package com.orderflow.engine.pricing.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the engine reads to price a cart at one moment: the currency policy of the tenant's
 * country and the products the tenant sells, with their list price and tax rate at that moment. The
 * engine reads nothing else, so the same cart and snapshot always give the same breakdown. A SKU
 * that is missing from the snapshot is unknown to the tenant, whether no tenant sells it or another
 * tenant does.
 */
public final class PricingSnapshot {

    private final CurrencyPolicy policy;
    private final Map<String, Product> productsBySku;

    public PricingSnapshot(CurrencyPolicy policy, List<Product> products) {
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
    }

    public CurrencyPolicy policy() {
        return policy;
    }

    /** The product sold under this SKU code, or empty when the tenant does not sell it. */
    public Optional<Product> product(String sku) {
        return Optional.ofNullable(productsBySku.get(sku));
    }

    /**
     * A product the tenant sells. The list price is tax-exclusive, and the tax rate is a fraction
     * between 0 and 1, as in the {@code products} table.
     */
    public record Product(String sku, Money listPrice, BigDecimal taxRate) {

        public Product {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(listPrice, "listPrice");
            Objects.requireNonNull(taxRate, "taxRate");
            if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "Tax rate of product " + sku + " must be between 0 and 1: " + taxRate);
            }
        }
    }
}
