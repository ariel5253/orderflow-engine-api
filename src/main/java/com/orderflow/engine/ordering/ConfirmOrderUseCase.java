package com.orderflow.engine.ordering;

import com.orderflow.engine.ordering.IdempotencyStore.CompletedRequest;
import com.orderflow.engine.ordering.OrderRejectedException.Code;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteStatus;
import com.orderflow.engine.quoting.QuoteView;
import com.orderflow.engine.quoting.SealedQuote;
import com.orderflow.engine.quoting.SnapshotProvider;
import com.orderflow.engine.quoting.StoredQuote;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms a quote as an order (SPEC section 7.1, api-contract E-03) in one ACID transaction that writes the
 * idempotency key, the credit consumption, the order, its credit movement, its outbox events and the stored response,
 * or none of them. Locks are always taken in the same order, idempotency key, then quote, then credit account, so two
 * confirmations can never deadlock, and every lock wait is bounded by {@link #LOCK_TIMEOUT}.
 *
 * <p>The order stores no amount and the quote is never priced again: its breakdown is rendered from the sealed quote
 * lines, so the confirmed total is the quoted total by construction (SPEC section 6). The method returns only after
 * the commit, so the caller answers with an order that already exists and can be read (I-201).
 */
public class ConfirmOrderUseCase {

    /**
     * How long a statement of the confirmation waits for a lock held by a concurrent one. A confirmation holds its
     * locks for milliseconds, so a longer wait means the other request is stuck, and a 409 the client can retry serves
     * it better than a request that hangs (RISK-01).
     */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);

    private final SnapshotProvider snapshots;
    private final IdempotencyStore idempotency;
    private final OrderRepository orders;
    private final QuoteRepository quotes;
    private final CreditLedger credit;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ConfirmOrderUseCase(SnapshotProvider snapshots, IdempotencyStore idempotency, OrderRepository orders,
            QuoteRepository quotes, CreditLedger credit, OutboxWriter outbox, Clock clock) {
        this.snapshots = snapshots;
        this.idempotency = idempotency;
        this.orders = orders;
        this.quotes = quotes;
        this.credit = credit;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Confirms the quote, or replays the answer of the request completed under the same key. A rejection rolls back
     * everything, the key included, so the client may fix the cause and retry with the same key.
     *
     * @param requestHash identifies the request among those sent with the same key
     * @param encoder writes the response body, which is stored under the key before the commit
     * @throws OrderRejectedException when the ordering rules reject the confirmation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Confirmation confirm(String tenantId, UUID customerId, String idempotencyKey, byte[] requestHash,
            UUID quoteId, OrderView.Encoder encoder) {
        if (snapshots.tenantPolicy(tenantId).isEmpty()) {
            throw new OrderRejectedException(Code.TENANT_NOT_FOUND, "Tenant '" + tenantId + "' does not exist");
        }
        // The database keeps microseconds, so the instant is cut to what is stored and reads back unchanged.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        orders.limitLockWaits(LOCK_TIMEOUT);

        Optional<CompletedRequest> completed =
                idempotency.claim(tenantId, customerId, idempotencyKey, requestHash, now);
        if (completed.isPresent()) {
            return replay(completed.get(), requestHash, idempotencyKey);
        }

        if (!orders.lockQuote(tenantId, customerId, quoteId)) {
            throw new OrderRejectedException(Code.QUOTE_NOT_FOUND,
                    "Quote " + quoteId + " does not belong to customer " + customerId + " of tenant '" + tenantId + "'");
        }
        Optional<PlacedOrder> existing = orders.findByQuote(tenantId, quoteId);
        if (existing.isPresent()) {
            throw OrderRejectedException.alreadyConfirmed(quoteId, existing.get());
        }
        StoredQuote stored = quotes.find(tenantId, customerId, quoteId).orElseThrow();
        SealedQuote sealed = stored.sealed();
        if (QuoteStatus.of(stored.confirmed(), sealed.expiresAt(), now) == QuoteStatus.EXPIRED) {
            throw new OrderRejectedException(Code.QUOTE_EXPIRED,
                    "Quote " + quoteId + " expired at " + sealed.expiresAt());
        }
        // Rendered as the order shows it once inserted below, from the sealed lines only: nothing is priced again.
        QuoteView quote = QuoteView.render(stored, QuoteStatus.CONFIRMED);
        Money total = quote.totals().total();
        boolean onCredit = sealed.paymentMethod() == PaymentMethod.CREDIT;

        if (onCredit && !credit.consume(tenantId, customerId, total)) {
            throw new OrderRejectedException(Code.CREDIT_LIMIT_EXCEEDED,
                    "Quote " + quoteId + " total " + total.amount().toPlainString() + " does not fit in the credit of"
                            + " customer " + customerId + " of tenant '" + tenantId + "'");
        }
        PlacedOrder order = orders.insert(tenantId, customerId, quoteId, now);
        if (onCredit) {
            credit.record(tenantId, order.orderId(), total);
        }
        outbox.orderConfirmed(tenantId, order.orderId(), now);
        byte[] responseBody = encoder.encode(new OrderView(order.orderId(), order.orderNumber(), quoteId, now,
                sealed.paymentMethod(), quote));
        idempotency.complete(tenantId, customerId, idempotencyKey, order.orderId(), responseBody);
        return Confirmation.created(order.orderId(), responseBody);
    }

    private static Confirmation replay(CompletedRequest completed, byte[] requestHash, String idempotencyKey) {
        if (!Arrays.equals(completed.requestHash(), requestHash)) {
            throw new OrderRejectedException(Code.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key '" + idempotencyKey + "' was first used with another request");
        }
        return Confirmation.replayed(completed.orderId(), completed.responseBody());
    }
}
