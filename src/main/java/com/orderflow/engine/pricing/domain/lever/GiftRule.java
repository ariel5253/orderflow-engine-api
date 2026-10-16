package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A gift rule of SPEC BR-04: when the paid cart meets the rule's condition, the customer receives
 * units of the gift SKU for free. A repeatable rule grants its gift once for each whole multiple of
 * its condition; any other rule grants it at most once.
 */
public record GiftRule(UUID id, Trigger trigger, String giftSku, long giftQty, boolean repeatable) {

    public GiftRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(giftSku, "giftSku");
        if (giftQty < 1) {
            throw new IllegalArgumentException("Gift rule " + id + " gives fewer than one unit: " + giftQty);
        }
    }

    /**
     * The condition of a gift rule, one of the three that the {@code gift_rules} table allows: a
     * minimum paid quantity of one SKU, a minimum paid quantity of one family, or a minimum gross
     * subtotal alone.
     */
    public sealed interface Trigger permits SkuQuantity, FamilyQuantity, GrossSubtotal {
    }

    /** At least {@code minQty} paid units of one SKU. */
    public record SkuQuantity(String sku, long minQty) implements Trigger {

        public SkuQuantity {
            Objects.requireNonNull(sku, "sku");
            if (minQty < 1) {
                throw new IllegalArgumentException(
                        "A gift condition needs at least one unit of SKU " + sku + ": " + minQty);
            }
        }
    }

    /** At least {@code minQty} paid units of the SKUs of one family, counted together. */
    public record FamilyQuantity(String family, long minQty) implements Trigger {

        public FamilyQuantity {
            Objects.requireNonNull(family, "family");
            if (minQty < 1) {
                throw new IllegalArgumentException(
                        "A gift condition needs at least one unit of family " + family + ": " + minQty);
            }
        }
    }

    /**
     * A gross subtotal of at least {@code minGross}: the paid units at their list price, before any
     * discount (A-10). It is above zero, because a gift that every cart earns is not a condition.
     */
    public record GrossSubtotal(Money minGross) implements Trigger {

        public GrossSubtotal {
            Objects.requireNonNull(minGross, "minGross");
            if (minGross.amount().signum() <= 0) {
                throw new IllegalArgumentException(
                        "A gross subtotal condition must be above zero: " + minGross.amount());
            }
        }
    }
}
