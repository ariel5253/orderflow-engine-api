package com.orderflow.engine.pricing.domain.lever;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A tier rule of SPEC BR-02: volume brackets on one SKU or on one family. A bracket starts at its
 * {@code from} unit, inclusive, and ends where the next bracket starts; the last bracket has no end.
 * Units are counted from 1. The brackets are marginal (A-01): a unit receives only the percent of
 * the bracket it falls in, so percents never stack. Units before the first bracket receive nothing.
 */
public record TierRule(UUID id, Scope scope, String target, List<Bracket> brackets) {

    /** What a rule targets: one SKU code, or one family code. */
    public enum Scope {
        SKU,
        FAMILY
    }

    public TierRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(brackets, "brackets");
        if (brackets.isEmpty()) {
            throw new IllegalArgumentException("Tier rule " + id + " has no bracket");
        }
        Set<Long> starts = new HashSet<>();
        for (Bracket bracket : brackets) {
            if (!starts.add(bracket.from())) {
                throw new IllegalArgumentException(
                        "Tier rule " + id + " has two brackets from unit " + bracket.from());
            }
        }
        brackets = brackets.stream().sorted(Comparator.comparingLong(Bracket::from)).toList();
    }

    /**
     * The exact, unrounded discount of the units at positions {@code first} to {@code last} of this
     * rule's count, each sold at {@code unitPrice}. An empty range, where {@code last} is below
     * {@code first}, has no discount.
     */
    public BigDecimal exactDiscount(long first, long last, BigDecimal unitPrice) {
        BigDecimal discount = BigDecimal.ZERO;
        for (int i = 0; i < brackets.size(); i++) {
            Bracket bracket = brackets.get(i);
            long start = Math.max(first, bracket.from());
            long end = i + 1 < brackets.size() ? Math.min(last, brackets.get(i + 1).from() - 1) : last;
            if (start <= end) {
                BigDecimal units = BigDecimal.valueOf(end - start + 1);
                discount = discount.add(unitPrice.multiply(units).multiply(bracket.percent()));
            }
        }
        return discount;
    }

    /**
     * One bracket. The percent is a fraction between 0 and 1, as in the {@code tier_brackets}
     * table, so 0.05 is 5%.
     */
    public record Bracket(long from, BigDecimal percent) {

        public Bracket {
            Objects.requireNonNull(percent, "percent");
            if (from < 1) {
                throw new IllegalArgumentException("A bracket starts at unit 1 or later: " + from);
            }
            if (percent.signum() < 0 || percent.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("A bracket percent must be between 0 and 1: " + percent);
            }
        }
    }
}
