package com.orderflow.engine.ordering;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A confirmation that the ordering rules reject (api-contract E-03). Each code is named after the public error code of
 * the API, so the web adapter maps it without a translation table. The message is for logs only: the user text is
 * resolved from the code. A rejection rolls the whole confirmation back, so nothing of it is stored, not even its
 * idempotency key (BD-02).
 */
public final class OrderRejectedException extends RuntimeException {

    /** The rejections of a confirmation that the request format itself does not cause. */
    public enum Code {
        TENANT_NOT_FOUND,
        QUOTE_NOT_FOUND,
        QUOTE_EXPIRED,
        QUOTE_ALREADY_CONFIRMED,
        CREDIT_LIMIT_EXCEEDED,
        IDEMPOTENCY_KEY_IN_PROGRESS,
        IDEMPOTENCY_KEY_REUSED
    }

    private final Code code;
    private final PlacedOrder existingOrder;

    public OrderRejectedException(Code code, String message) {
        this(code, message, null, null);
    }

    /**
     * A wait for a lock that a concurrent confirmation holds passed the lock timeout of the transaction (RISK-01). The
     * client may retry: by then the concurrent confirmation has ended.
     */
    public static OrderRejectedException lockWaitTimedOut(String message, Throwable cause) {
        return new OrderRejectedException(Code.IDEMPOTENCY_KEY_IN_PROGRESS, message, null, cause);
    }

    /**
     * The quote is confirmed by an order already. The rejection carries that order, so a client that lost the first
     * answer, for instance after reinstalling the app, can still find it (BD-03).
     */
    public static OrderRejectedException alreadyConfirmed(UUID quoteId, PlacedOrder existingOrder) {
        Objects.requireNonNull(existingOrder, "existingOrder");
        return new OrderRejectedException(Code.QUOTE_ALREADY_CONFIRMED,
                "Quote " + quoteId + " is confirmed by order " + existingOrder.orderNumber(), existingOrder, null);
    }

    private OrderRejectedException(Code code, String message, PlacedOrder existingOrder, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.existingOrder = existingOrder;
    }

    public Code code() {
        return code;
    }

    /** The order that already confirms the quote, present only with {@link Code#QUOTE_ALREADY_CONFIRMED}. */
    public Optional<PlacedOrder> existingOrder() {
        return Optional.ofNullable(existingOrder);
    }
}
