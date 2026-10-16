package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.PricingSnapshot;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads what a quote is priced with, at the moment the quote is created (SPEC §6, step 2). Every call reads the
 * current data, so a later price or lever change reaches new quotes only: a sealed quote is never priced again.
 */
public interface SnapshotProvider {

    /** The policy of the tenant's country, or empty when no tenant has this id. */
    Optional<TenantPolicy> tenantPolicy(String tenantId);

    /** Whether the customer belongs to the tenant. A customer of another tenant does not. */
    boolean customerExists(String tenantId, UUID customerId);

    /**
     * What the engine prices a cart with, under the policy of the tenant's country: the tenant's products that carry
     * one of these SKU codes, and its active tier, combo and gift rules, with the product each active gift rule gives.
     * A SKU the tenant does not sell is left out, so the engine rejects it. An inactive rule is never read, so a
     * deactivated lever reaches no new quote. A stored price with more decimals than the currency scale is rejected
     * when it is read (data model DM-01).
     */
    PricingSnapshot pricingSnapshot(String tenantId, TenantPolicy policy, Set<String> skus);
}
