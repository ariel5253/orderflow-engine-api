package com.orderflow.engine.quoting;

import java.time.Instant;

/**
 * The state of a quote (SPEC BR-07). It is derived when the quote is read and never stored, so a quote row is never
 * updated.
 */
public enum QuoteStatus {
    OPEN,
    CONFIRMED,
    EXPIRED;

    /**
     * CONFIRMED once an order exists for the quote, otherwise EXPIRED when {@code now} is after {@code expiresAt},
     * otherwise OPEN. A quote is still open at the instant it expires.
     */
    public static QuoteStatus of(boolean confirmed, Instant expiresAt, Instant now) {
        if (confirmed) {
            return CONFIRMED;
        }
        return now.isAfter(expiresAt) ? EXPIRED : OPEN;
    }
}
