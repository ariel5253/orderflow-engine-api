package com.orderflow.engine.pricing.domain;

import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a cart before it is priced (SPEC BR-00). It rejects an empty cart and any line whose
 * quantity is below one, merges the lines of a repeated SKU by adding their quantities, and sorts
 * the lines by SKU code. The engine prices only the normalized cart, so a breakdown never depends
 * on the order of the client's lines. Normalizing a normalized cart returns an equal cart.
 */
public final class CartNormalizer {

    private CartNormalizer() {
    }

    public static Cart normalize(Cart cart) {
        if (cart.lines().isEmpty()) {
            throw new CartRejectedException(Code.CART_EMPTY, "The cart has no lines");
        }
        // Sorting first makes the SKU named by a rejection independent of the client's order too.
        List<CartLine> bySku = cart.lines().stream()
                .sorted(Comparator.comparing(CartLine::sku))
                .toList();
        Map<String, Long> qtyBySku = new LinkedHashMap<>();
        for (CartLine line : bySku) {
            // Each line is checked before it is merged, so a larger line of the same SKU cannot
            // hide it.
            if (line.qty() <= 0) {
                throw new CartRejectedException(Code.INVALID_QUANTITY,
                        "SKU '" + line.sku() + "' has a quantity below one");
            }
            qtyBySku.merge(line.sku(), line.qty(),
                    (merged, added) -> add(line.sku(), merged, added));
        }
        return new Cart(qtyBySku.entrySet().stream()
                .map(entry -> new CartLine(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static long add(String sku, long merged, long added) {
        try {
            return Math.addExact(merged, added);
        } catch (ArithmeticException overflow) {
            throw new CartRejectedException(Code.INVALID_QUANTITY,
                    "The merged quantity of SKU '" + sku + "' does not fit a long");
        }
    }
}
