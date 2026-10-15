package com.orderflow.engine.quoting;

import java.util.Optional;
import java.util.UUID;

/**
 * Where sealed quotes live. Quotes are insert-only, so this port has no operation that changes a stored quote (data
 * model DM-02).
 */
public interface QuoteRepository {

    /** Stores the quote with its lines and their discount components, and returns the id the quote was given. */
    UUID insert(SealedQuote quote);

    /**
     * The quote with this id, only when it belongs to this tenant and this customer. A quote of anyone else is not
     * found, so its existence is never revealed (BD-01).
     */
    Optional<StoredQuote> find(String tenantId, UUID customerId, UUID quoteId);
}
