package com.orderflow.engine.quoting;

import com.orderflow.engine.quoting.QuoteRejectedException.Code;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a quote back (api-contract E-02). Its status is derived now (BR-07). While it is OPEN, a CREDIT quote carries
 * the advisory credit check against the customer's credit position as it is now. Once it is CONFIRMED or EXPIRED the
 * check no longer applies, so it is left out.
 */
public class GetQuoteUseCase {

    private final SnapshotProvider snapshots;
    private final QuoteRepository quotes;
    private final CreditAdvisor creditAdvisor;
    private final Clock clock;

    public GetQuoteUseCase(SnapshotProvider snapshots, QuoteRepository quotes, CreditAdvisor creditAdvisor,
            Clock clock) {
        this.snapshots = snapshots;
        this.quotes = quotes;
        this.creditAdvisor = creditAdvisor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuoteView get(String tenantId, UUID customerId, UUID quoteId) {
        if (snapshots.tenantPolicy(tenantId).isEmpty()) {
            throw new QuoteRejectedException(Code.TENANT_NOT_FOUND, "Tenant '" + tenantId + "' does not exist");
        }
        StoredQuote stored = quotes.find(tenantId, customerId, quoteId).orElseThrow(() -> new QuoteRejectedException(
                Code.QUOTE_NOT_FOUND,
                "Quote " + quoteId + " does not belong to customer " + customerId + " of tenant '" + tenantId + "'"));
        QuoteStatus status = QuoteStatus.of(stored.confirmed(), stored.sealed().expiresAt(), clock.instant());
        QuoteView quote = QuoteView.render(stored, status);
        if (status == QuoteStatus.OPEN && stored.sealed().paymentMethod() == PaymentMethod.CREDIT) {
            return quote.withCredit(
                    creditAdvisor.advise(tenantId, customerId, quote.totals().total()).orElse(null));
        }
        return quote;
    }
}
