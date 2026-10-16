package com.orderflow.engine.pricing.domain;

import java.util.List;

/**
 * The lines a client asks to price, in the order it sent them. A SKU may appear more than once.
 * {@link CartNormalizer} turns it into the normalized cart that the engine prices (SPEC BR-00).
 */
public record Cart(List<CartLine> lines) {

    public Cart {
        lines = List.copyOf(lines);
    }
}
