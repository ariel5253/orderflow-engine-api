package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.Breakdown;
import com.orderflow.engine.pricing.domain.Cart;
import com.orderflow.engine.pricing.domain.CartLine;
import com.orderflow.engine.pricing.domain.CartNormalizer;
import com.orderflow.engine.pricing.domain.CartRejectedException;
import com.orderflow.engine.pricing.domain.LineBreakdown;
import com.orderflow.engine.pricing.domain.LineBreakdown.GiftReference;
import com.orderflow.engine.pricing.domain.PricingEngine;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
import com.orderflow.engine.quoting.QuoteRejectedException.Code;
import com.orderflow.engine.quoting.SealedQuote.Adjustment;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a cart with the tenant's prices, tax rates and active levers, and seals the result as a quote (SPEC §6,
 * api-contract E-01). The quote and its paid and gift lines, with their tier discounts and combo allocations, are
 * written in one transaction, so a quote is either stored whole or not at all, and are never updated afterwards. A
 * CREDIT quote also carries the advisory credit check of BR-06, which reserves nothing (A-08).
 */
public class CreateQuoteUseCase {

    /**
     * A quote line stores its quantity as a 32-bit integer (data model §4), so a larger quantity is rejected as input
     * instead of failing when the line is written.
     */
    private static final long MAX_LINE_QTY = Integer.MAX_VALUE;

    private final SnapshotProvider snapshots;
    private final CreditAdvisor creditAdvisor;
    private final QuoteRepository quotes;
    private final PricingEngine engine;
    private final Clock clock;

    public CreateQuoteUseCase(SnapshotProvider snapshots, CreditAdvisor creditAdvisor, QuoteRepository quotes,
            PricingEngine engine, Clock clock) {
        this.snapshots = snapshots;
        this.creditAdvisor = creditAdvisor;
        this.quotes = quotes;
        this.engine = engine;
        this.clock = clock;
    }

    @Transactional
    public QuoteView create(String tenantId, UUID customerId, PaymentMethod paymentMethod, Cart cart) {
        Cart normalized = CartNormalizer.normalize(cart);
        requireStorableQuantities(normalized);
        TenantPolicy policy = snapshots.tenantPolicy(tenantId).orElseThrow(() -> new QuoteRejectedException(
                Code.TENANT_NOT_FOUND, "Tenant '" + tenantId + "' does not exist"));
        if (!snapshots.customerExists(tenantId, customerId)) {
            throw new QuoteRejectedException(Code.CUSTOMER_NOT_FOUND,
                    "Customer " + customerId + " does not belong to tenant '" + tenantId + "'");
        }
        Set<String> skus = normalized.lines().stream().map(CartLine::sku).collect(Collectors.toSet());
        PricingSnapshot snapshot = snapshots.pricingSnapshot(tenantId, policy, skus);
        Breakdown breakdown = engine.price(normalized, snapshot);
        CreditAdvice credit = paymentMethod == PaymentMethod.CREDIT
                ? creditAdvisor.advise(tenantId, customerId, breakdown.totals().total())
                        .orElseThrow(() -> new QuoteRejectedException(Code.CREDIT_NOT_ENABLED,
                                "Customer " + customerId + " of tenant '" + tenantId + "' has no credit account"))
                : null;
        // The database keeps microseconds, so the instants are cut to what is stored.
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID quoteId = quotes.insert(new SealedQuote(tenantId, customerId, paymentMethod, policy.currency(),
                createdAt, createdAt.plus(policy.quoteTtl()), lines(breakdown, snapshot)));
        // The new quote is rendered from what was stored, so it reads back with the same body.
        StoredQuote stored = quotes.find(tenantId, customerId, quoteId).orElseThrow();
        QuoteStatus status = QuoteStatus.of(stored.confirmed(), stored.sealed().expiresAt(), createdAt);
        return QuoteView.render(stored, status).withCredit(credit);
    }

    private static void requireStorableQuantities(Cart normalized) {
        for (CartLine line : normalized.lines()) {
            if (line.qty() > MAX_LINE_QTY) {
                throw new CartRejectedException(CartRejectedException.Code.INVALID_QUANTITY,
                        "SKU '" + line.sku() + "' has a quantity above " + MAX_LINE_QTY);
            }
        }
    }

    /**
     * Seals each line of the breakdown, in its order: the paid lines of the normalized cart in SKU order, then the gift
     * lines. A paid line keeps its tier discount and its part of each combo as its discount components, already rounded
     * (RP-2). A gift line keeps the rule that granted it, and its product's list price as its unit price, so its
     * reference value reads back as the engine priced it, quantity times list price (data model §4). Every line keeps
     * the taxable base and tax the engine gave it, so a gift line keeps what its country's gift tax policy decided.
     */
    private static List<SealedQuote.Line> lines(Breakdown breakdown, PricingSnapshot snapshot) {
        return breakdown.lines().stream()
                .map(line -> new SealedQuote.Line(line.sku(),
                        line.gift().map(GiftReference::ruleId).orElse(null),
                        line.qty(),
                        // The engine gives a gift line a unit price of zero, because the customer pays nothing for
                        // it. The sealed unit price is its product's list price, which gives its reference value
                        // (data model §4); the snapshot sells every gift SKU, so the product is always there.
                        line.isGift() ? snapshot.product(line.sku()).orElseThrow().listPrice() : line.unitPrice(),
                        line.taxRate(), line.taxableBase(), line.tax(), adjustments(line)))
                .toList();
    }

    /** The discount components of a line: its tier discounts, then its part of each combo, in combo order. */
    private static List<Adjustment> adjustments(LineBreakdown line) {
        return Stream.concat(
                        line.discounts().stream().map(discount -> new Adjustment(
                                Adjustment.Kind.valueOf(discount.kind().name()), discount.ruleId(), discount.amount())),
                        line.comboAllocations().stream().map(allocation -> new Adjustment(
                                Adjustment.Kind.COMBO, allocation.comboId(), allocation.amount())))
                .toList();
    }
}
