package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits an amount in proportion to weights with the largest-remainder method of SPEC BR-03, so the
 * shares always sum exactly to the amount (RF-3):
 * <ol>
 *   <li>each share is truncated to the currency scale;</li>
 *   <li>the leftover minor units go, one each, to the largest fractional remainders;</li>
 *   <li>ties go by SKU-code order.</li>
 * </ol>
 */
public final class LargestRemainderAllocator {

    private LargestRemainderAllocator() {
    }

    /**
     * Returns the share of the amount for each SKU of the weights, keyed by SKU code. The amount is
     * not negative, no weight is negative, and at least one weight is above zero.
     */
    public static Map<String, Money> allocate(Money amount, Map<String, Money> weights) {
        CurrencyPolicy policy = amount.policy();
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("Cannot split " + amount.amount() + " over no weight");
        }
        if (amount.amount().signum() < 0) {
            throw new IllegalArgumentException("Cannot split a negative amount: " + amount.amount());
        }
        weights.forEach((sku, weight) -> {
            if (weight.amount().signum() < 0) {
                throw new IllegalArgumentException("SKU " + sku + " has a negative weight: " + weight.amount());
            }
        });
        Money weightSum = weights.values().stream().reduce(Money.zero(policy), Money::plus);
        if (weightSum.amount().signum() == 0) {
            throw new IllegalArgumentException(
                    "Cannot split " + amount.amount() + " over weights that are all zero");
        }
        // Money is held at the currency scale, so its unscaled value counts minor units. Each exact
        // share is amount x weight / sum: its quotient is the truncated share, and its remainder,
        // over the same sum for every SKU, orders the fractions exactly, with no division precision
        // to choose.
        BigInteger units = amount.amount().unscaledValue();
        BigInteger sum = weightSum.amount().unscaledValue();
        List<Share> shares = weights.entrySet().stream()
                .map(weight -> new Share(weight.getKey(),
                        units.multiply(weight.getValue().amount().unscaledValue()).divideAndRemainder(sum)))
                .sorted(Comparator.comparing(Share::remainder).reversed().thenComparing(Share::sku))
                .toList();
        // Each truncation drops less than one minor unit, so fewer units are left over than there
        // are shares, and the first shares in remainder order take one each.
        int leftover = shares.stream().map(Share::truncated).reduce(units, BigInteger::subtract).intValueExact();
        Map<String, Money> allocations = new HashMap<>();
        for (int i = 0; i < shares.size(); i++) {
            Share share = shares.get(i);
            BigInteger minorUnits = i < leftover ? share.truncated().add(BigInteger.ONE) : share.truncated();
            allocations.put(share.sku(), new Money(new BigDecimal(minorUnits, policy.scale()), policy));
        }
        return Map.copyOf(allocations);
    }

    /** One SKU's exact share, as its truncated minor units and the remainder of the division. */
    private record Share(String sku, BigInteger truncated, BigInteger remainder) {

        Share(String sku, BigInteger[] quotientAndRemainder) {
            this(sku, quotientAndRemainder[0], quotientAndRemainder[1]);
        }
    }
}
