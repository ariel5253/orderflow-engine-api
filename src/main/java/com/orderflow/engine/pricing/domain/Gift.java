package com.orderflow.engine.pricing.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A gift that a rule of SPEC BR-04 grants: units of a SKU the customer receives for free. Each gift
 * is priced as its own gift line, which never merges with a paid line of the same SKU, and the totals
 * list it with its quantity and origin.
 */
public record Gift(UUID ruleId, String sku, long qty) {

    public Gift {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(sku, "sku");
        if (qty < 1) {
            throw new IllegalArgumentException("A gift of SKU " + sku + " needs at least one unit: " + qty);
        }
    }

    /**
     * The origin of a gift as the API shows it, {@code GIFT:<ruleId>}. The totals entry and the gift
     * line both take it from here, so they always agree.
     */
    public static String origin(UUID ruleId) {
        return "GIFT:" + ruleId;
    }

    /** The origin as the API shows it, {@code GIFT:<ruleId>}. */
    public String origin() {
        return origin(ruleId);
    }
}
