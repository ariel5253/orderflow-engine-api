package com.orderflow.engine.pricing.domain;

import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import java.util.List;
import java.util.function.Function;

/**
 * Prices a cart against a snapshot. The engine is pure and deterministic: it reads only its two
 * arguments, keeps no state and never reads a clock, so the same cart and snapshot always give the
 * same breakdown. It prices the cart normalized by SPEC BR-00 and runs the steps of BR-01 in order.
 * The only rounding is the tax of each line (RP-4).
 */
public final class PricingEngine {

    public Breakdown price(Cart cart, PricingSnapshot snapshot) {
        CurrencyPolicy policy = snapshot.policy();
        List<LineBreakdown> lines = CartNormalizer.normalize(cart).lines().stream()
                .map(line -> priceLine(line, productOf(line, snapshot)))
                .toList();
        return new Breakdown(policy.currencyCode(), lines, totals(lines, policy));
    }

    private static Product productOf(CartLine line, PricingSnapshot snapshot) {
        return snapshot.product(line.sku()).orElseThrow(() -> new CartRejectedException(
                Code.UNKNOWN_SKU, "SKU '" + line.sku() + "' is not sold by this tenant"));
    }

    private static LineBreakdown priceLine(CartLine line, Product product) {
        Money gross = product.listPrice().times(line.qty());
        // Steps 4 and 5: the line has no discount component, so its net and its taxable base are
        // its gross.
        Money net = gross;
        Money taxableBase = net;
        // Step 6: each line's tax is rounded on its own (RP-4), as on an electronic invoice.
        Money tax = taxableBase.timesRounded(product.taxRate());
        return new LineBreakdown(line.sku(), line.qty(), product.listPrice(), gross, net,
                taxableBase, product.taxRate(), tax);
    }

    // Step 7: totals are sums of rounded line values and are never rounded again (RP-5).
    private static Totals totals(List<LineBreakdown> lines, CurrencyPolicy policy) {
        Money taxes = sum(lines, LineBreakdown::tax, policy);
        return new Totals(
                sum(lines, LineBreakdown::gross, policy),
                sum(lines, LineBreakdown::taxableBase, policy),
                taxes,
                sum(lines, LineBreakdown::net, policy).plus(taxes));
    }

    private static Money sum(List<LineBreakdown> lines, Function<LineBreakdown, Money> amount,
            CurrencyPolicy policy) {
        return lines.stream().map(amount).reduce(Money.zero(policy), Money::plus);
    }
}
