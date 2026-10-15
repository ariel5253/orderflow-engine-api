package com.orderflow.engine.pricing.domain;

import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboStep;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep.Units;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Prices a cart against a snapshot. The engine is pure and deterministic: it reads only its two
 * arguments, keeps no state and never reads a clock, so the same cart and snapshot always give the
 * same breakdown. It prices the cart normalized by SPEC BR-00 and runs the steps of BR-01 in order.
 * Amounts are rounded only per line: each discount component (RP-2) and each tax (RP-4).
 */
public final class PricingEngine {

    public Breakdown price(Cart cart, PricingSnapshot snapshot) {
        CurrencyPolicy policy = snapshot.policy();
        List<PaidLine> paid = CartNormalizer.normalize(cart).lines().stream()
                .map(line -> new PaidLine(productOf(line, snapshot), line.qty()))
                .toList();
        // Step 2: a combo prices its units first, so the units it consumes cannot also earn a tier
        // discount (no double dipping).
        ComboStep.Result combos = ComboStep.apply(snapshot.comboRules(), paid.stream()
                .map(line -> new Units(line.product(), line.qty()))
                .toList());
        // Step 3 counts only the paid units no combo consumed. A gift is never a paid line, so its
        // units never reach a tier threshold either (A-04).
        Map<String, Discount> tierDiscounts =
                TierDiscountStep.apply(snapshot.tierRules(), combos.unconsumed());
        List<LineBreakdown> lines = paid.stream()
                .map(line -> priceLine(line,
                        Optional.ofNullable(tierDiscounts.get(line.product().sku())).stream().toList(),
                        combos.allocationsOf(line.product().sku())))
                .toList();
        return new Breakdown(policy.currencyCode(), lines, totals(lines, policy));
    }

    private static Product productOf(CartLine line, PricingSnapshot snapshot) {
        return snapshot.product(line.sku()).orElseThrow(() -> new CartRejectedException(
                Code.UNKNOWN_SKU, "SKU '" + line.sku() + "' is not sold by this tenant"));
    }

    private static LineBreakdown priceLine(PaidLine line, List<Discount> discounts,
            List<ComboAllocation> comboAllocations) {
        Product product = line.product();
        Money gross = product.listPrice().times(line.qty());
        // Steps 4 and 5: commercial discounts, the line's part of each combo included, are settled
        // before tax, so the taxable base is the net, which is exact because each component is
        // already rounded (RP-3).
        Money net = Stream.concat(discounts.stream().map(Discount::amount),
                        comboAllocations.stream().map(ComboAllocation::amount))
                .reduce(gross, Money::minus);
        Money taxableBase = net;
        // Step 6: each line's tax is rounded on its own (RP-4), as on an electronic invoice.
        Money tax = taxableBase.timesRounded(product.taxRate());
        return new LineBreakdown(product.sku(), line.qty(), product.listPrice(), gross, discounts,
                comboAllocations, net, taxableBase, product.taxRate(), tax);
    }

    // Step 7: totals are sums of rounded line values and are never rounded again (RP-5).
    private static Totals totals(List<LineBreakdown> lines, CurrencyPolicy policy) {
        Money taxes = sum(lines, LineBreakdown::tax, policy);
        return new Totals(
                sum(lines, LineBreakdown::gross, policy),
                discountsByOrigin(lines),
                sum(lines, LineBreakdown::taxableBase, policy),
                taxes,
                sum(lines, LineBreakdown::net, policy).plus(taxes));
    }

    // One entry per origin, so a family rule spread over several lines is one discount, and a combo
    // is one COMBO entry whose amount is the sum of its allocations, its whole discount (A-07).
    // Sorting by origin keeps the order independent of the line order.
    private static List<Discount> discountsByOrigin(List<LineBreakdown> lines) {
        Map<String, Discount> byOrigin = new TreeMap<>();
        lines.stream().flatMap(PricingEngine::discountsOf).forEach(discount -> byOrigin.merge(
                discount.origin(), discount, (merged, added) -> new Discount(
                        merged.kind(), merged.ruleId(), merged.amount().plus(added.amount()))));
        return List.copyOf(byOrigin.values());
    }

    private static Stream<Discount> discountsOf(LineBreakdown line) {
        return Stream.concat(line.discounts().stream(), line.comboAllocations().stream()
                .map(part -> new Discount(Discount.Kind.COMBO, part.comboId(), part.amount())));
    }

    private static Money sum(List<LineBreakdown> lines, Function<LineBreakdown, Money> amount,
            CurrencyPolicy policy) {
        return lines.stream().map(amount).reduce(Money.zero(policy), Money::plus);
    }

    /** A line of the normalized cart, with the product its SKU names in the snapshot. */
    private record PaidLine(Product product, long qty) {
    }
}
