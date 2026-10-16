package com.orderflow.engine.pricing.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A discount component with its origin, the lever and the rule that produced it (SPEC section 8).
 * The amount is already rounded to the currency scale (RP-2) and is always positive: a component
 * that rounds to zero is never emitted.
 */
public record Discount(Kind kind, UUID ruleId, Money amount) {

    /** The lever that produced the component. */
    public enum Kind {
        TIER
    }

    public Discount {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(amount, "amount");
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "A discount component must be positive: " + kind + ":" + ruleId + " " + amount.amount());
        }
    }

    /** The origin as the API shows it, for example {@code TIER:<ruleId>}. */
    public String origin() {
        return kind + ":" + ruleId;
    }
}
