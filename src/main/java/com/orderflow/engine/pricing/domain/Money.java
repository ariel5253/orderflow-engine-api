package com.orderflow.engine.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An amount of money in one currency policy. Amounts are compared with {@link #compareTo(Money)}.
 * The amount is always held at the policy scale, so the record {@code equals} agrees with
 * {@code compareTo}. An amount with more decimals than the policy scale is rejected: rounding is
 * explicit and goes only through {@link CurrencyPolicy#round(BigDecimal)}, either directly or
 * through {@link #timesRounded(BigDecimal)}, the shortcut for one rate product.
 */
public record Money(BigDecimal amount, CurrencyPolicy policy) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(policy, "policy");
        if (amount.scale() > policy.scale()) {
            throw new IllegalArgumentException("Amount " + amount + " has more decimals than the "
                    + policy.currencyCode() + " scale " + policy.scale());
        }
        amount = amount.setScale(policy.scale());
    }

    public static Money zero(CurrencyPolicy policy) {
        return new Money(BigDecimal.ZERO, policy);
    }

    /** Exact addition (rounding point RP-5). */
    public Money plus(Money other) {
        requireSamePolicy(other);
        return new Money(amount.add(other.amount), policy);
    }

    /** Exact subtraction (rounding point RP-3). */
    public Money minus(Money other) {
        requireSamePolicy(other);
        return new Money(amount.subtract(other.amount), policy);
    }

    /** Exact multiplication by a unit quantity (rounding point RP-1). */
    public Money times(long quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), policy);
    }

    /**
     * Multiplies by a rate and rounds the exact product once with the currency policy (rounding
     * points RP-2 and RP-4).
     */
    public Money timesRounded(BigDecimal rate) {
        return new Money(policy.round(amount.multiply(rate)), policy);
    }

    @Override
    public int compareTo(Money other) {
        requireSamePolicy(other);
        return amount.compareTo(other.amount);
    }

    private void requireSamePolicy(Money other) {
        if (!policy.equals(other.policy)) {
            throw new IllegalArgumentException("Cannot combine " + policy + " with " + other.policy);
        }
    }
}
