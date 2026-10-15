package com.orderflow.engine.pricing.domain;

import java.util.Objects;

/**
 * One line of a cart: a SKU code and a quantity of units. Its quantity is checked by
 * {@link CartNormalizer} together with the rest of the cart (SPEC BR-00).
 */
public record CartLine(String sku, long qty) {

    public CartLine {
        Objects.requireNonNull(sku, "sku");
    }
}
