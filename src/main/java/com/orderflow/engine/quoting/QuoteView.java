package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.SealedQuote.Adjustment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * A quote in the breakdown shape of SPEC §8, the same for a new quote and a quote read back. It is rendered from the
 * sealed facts of {@link StoredQuote} by exact arithmetic only: nothing is rounded here, because every value that
 * needed a rounding was rounded per line and stored when the quote was sealed (RP-2, RP-4). {@code credit} is null
 * when the advisory credit check does not apply.
 */
public record QuoteView(
        UUID quoteId,
        QuoteStatus status,
        Instant expiresAt,
        String currency,
        List<Line> lines,
        Totals totals,
        CreditAdvice credit) {

    public QuoteView {
        lines = List.copyOf(lines);
    }

    /**
     * One line of the breakdown. {@code origin} and {@code referenceValue} belong to a gift line and are null on a paid
     * line.
     */
    public record Line(
            String sku,
            String description,
            long qty,
            boolean gift,
            String origin,
            Money unitPrice,
            Money gross,
            List<Discount> discounts,
            List<ComboAllocation> comboAllocations,
            Money net,
            Money referenceValue,
            Money taxableBase,
            BigDecimal taxRate,
            Money tax) {

        public Line {
            discounts = List.copyOf(discounts);
            comboAllocations = List.copyOf(comboAllocations);
        }
    }

    /** A discount with its origin: {@code TIER:<ruleId>} on a line, also {@code COMBO:<ruleId>} in the totals. */
    public record Discount(String origin, Money amount) {
    }

    /** The share of a combo discount that a line carries. It only derives the line's taxable base (A-07). */
    public record ComboAllocation(UUID comboId, Money amount) {
    }

    /** A gift with its quantity and origin, {@code GIFT:<ruleId>}. */
    public record Gift(String sku, long qty, String origin) {
    }

    /** The totals of the quote: sums of the line values, never rounded again (RP-5). */
    public record Totals(
            Money grossSubtotal,
            List<Discount> discounts,
            List<Gift> gifts,
            Money taxableBase,
            Money taxes,
            Money total) {

        public Totals {
            discounts = List.copyOf(discounts);
            gifts = List.copyOf(gifts);
        }
    }

    /** The same quote with the result of the advisory credit check, or with none when {@code credit} is null. */
    public QuoteView withCredit(CreditAdvice credit) {
        return new QuoteView(quoteId, status, expiresAt, currency, lines, totals, credit);
    }

    /**
     * Renders a stored quote in the given status, with no credit check (data model §6). The order of a confirmed quote
     * is rendered this way too, so quote and order always show the same figures.
     */
    public static QuoteView render(StoredQuote stored, QuoteStatus status) {
        SealedQuote sealed = stored.sealed();
        CurrencyPolicy currency = sealed.currency();
        List<Line> lines = sealed.lines().stream()
                .map(line -> line(line, stored.descriptions().get(line.sku()), currency))
                .toList();
        return new QuoteView(stored.id(), status, sealed.expiresAt(), currency.currencyCode(), lines,
                totals(sealed, lines, currency), null);
    }

    private static Line line(SealedQuote.Line line, String description, CurrencyPolicy currency) {
        Money listValue = line.unitPrice().times(line.qty());
        // A gift costs the customer nothing: its list value is shown only as its reference value (BR-04).
        Money unitPrice = line.isGift() ? Money.zero(currency) : line.unitPrice();
        Money gross = line.isGift() ? Money.zero(currency) : listValue;
        Money net = line.adjustments().stream().map(Adjustment::amount).reduce(gross, Money::minus);
        List<Discount> discounts = line.adjustments().stream()
                .filter(adjustment -> adjustment.kind() == Adjustment.Kind.TIER)
                .map(adjustment -> new Discount(adjustment.origin(), adjustment.amount()))
                .toList();
        List<ComboAllocation> comboAllocations = line.adjustments().stream()
                .filter(adjustment -> adjustment.kind() == Adjustment.Kind.COMBO)
                .map(adjustment -> new ComboAllocation(adjustment.ruleId(), adjustment.amount()))
                .toList();
        return new Line(line.sku(), description, line.qty(), line.isGift(), line.isGift() ? giftOrigin(line) : null,
                unitPrice, gross, discounts, comboAllocations, net, line.isGift() ? listValue : null,
                line.taxableBase(), line.taxRate(), line.tax());
    }

    private static Totals totals(SealedQuote sealed, List<Line> lines, CurrencyPolicy currency) {
        // One entry per origin, so a combo spread over several lines is one discount (BR-03). Sorting by origin keeps
        // the order independent of the line order.
        Map<String, Money> discountsByOrigin = new TreeMap<>();
        sealed.lines().stream()
                .flatMap(line -> line.adjustments().stream())
                .forEach(adjustment -> discountsByOrigin.merge(adjustment.origin(), adjustment.amount(), Money::plus));
        List<Gift> gifts = sealed.lines().stream()
                .filter(SealedQuote.Line::isGift)
                .map(line -> new Gift(line.sku(), line.qty(), giftOrigin(line)))
                .toList();
        Money taxes = sum(lines, Line::tax, currency);
        return new Totals(
                sum(lines, Line::gross, currency),
                discountsByOrigin.entrySet().stream()
                        .map(entry -> new Discount(entry.getKey(), entry.getValue()))
                        .toList(),
                gifts,
                sum(lines, Line::taxableBase, currency),
                taxes,
                sum(lines, Line::net, currency).plus(taxes));
    }

    private static String giftOrigin(SealedQuote.Line line) {
        return "GIFT:" + line.giftRuleId();
    }

    private static Money sum(List<Line> lines, Function<Line, Money> amount, CurrencyPolicy currency) {
        return lines.stream().map(amount).reduce(Money.zero(currency), Money::plus);
    }
}
