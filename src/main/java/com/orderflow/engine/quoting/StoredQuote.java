package com.orderflow.engine.quoting;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A sealed quote as it is read back: its id, its sealed facts, the current name of each product it quotes, keyed by
 * SKU, and whether an order confirms it. Names are read, not captured, because a name is not part of the price (data
 * model §6).
 */
public record StoredQuote(UUID id, SealedQuote sealed, Map<String, String> descriptions, boolean confirmed) {

    public StoredQuote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sealed, "sealed");
        descriptions = Map.copyOf(descriptions);
    }
}
