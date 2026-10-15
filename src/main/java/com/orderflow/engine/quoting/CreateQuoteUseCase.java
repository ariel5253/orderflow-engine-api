package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.Breakdown;
import com.orderflow.engine.pricing.domain.Cart;
import com.orderflow.engine.pricing.domain.CartLine;
import com.orderflow.engine.pricing.domain.CartNormalizer;
import com.orderflow.engine.pricing.domain.CartRejectedException;
import com.orderflow.engine.pricing.domain.PricingEngine;
import com.orderflow.engine.quoting.QuoteRejectedException.Code;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a cart and seals the result as a quote (SPEC §6, api-contract E-01). The quote, its lines and their discount
 * components are written in one transaction, so a quote is either stored whole or not at all, and are never updated
 * afterwards. A CREDIT quote also carries the advisory credit check of BR-06, which reserves nothing (A-08).
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
        Breakdown breakdown = engine.price(normalized,
                snapshots.pricingSnapshot(tenantId, policy.currency(), skus));
        CreditAdvice credit = paymentMethod == PaymentMethod.CREDIT
                ? creditAdvisor.advise(tenantId, customerId, breakdown.totals().total())
                        .orElseThrow(() -> new QuoteRejectedException(Code.CREDIT_NOT_ENABLED,
                                "Customer " + customerId + " of tenant '" + tenantId + "' has no credit account"))
                : null;
        // The database keeps microseconds, so the instants are cut to what is stored.
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID quoteId = quotes.insert(new SealedQuote(tenantId, customerId, paymentMethod, policy.currency(),
                createdAt, createdAt.plus(policy.quoteTtl()), lines(breakdown)));
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
     * Every line of the breakdown is a paid line of the normalized cart, in SKU order, and the breakdown carries no
     * discount component, so each sealed line is a paid line with no adjustment.
     */
    private static List<SealedQuote.Line> lines(Breakdown breakdown) {
        return breakdown.lines().stream()
                .map(line -> new SealedQuote.Line(line.sku(), null, line.qty(), line.unitPrice(), line.taxRate(),
                        line.taxableBase(), line.tax(), List.of()))
                .toList();
    }
}
