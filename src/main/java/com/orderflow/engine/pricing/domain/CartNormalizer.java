package com.orderflow.engine.pricing.domain;

import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a cart before it is priced (SPEC BR-00). It rejects an empty cart and any quantity outside 1 to
 * {@link #MAX_QTY}, merges the lines of a repeated SKU by adding their quantities, applies the same bound to the merged
 * quantity, and sorts the lines by SKU code. The engine prices only the normalized cart, so a breakdown never depends
 * on the order of the client's lines. Normalizing a normalized cart returns an equal cart.
 */
public final class CartNormalizer {

    /**
     * The largest quantity of a line, also after the lines of a repeated SKU are merged, and of a gift a rule derives
     * (SPEC BR-00). A quote line stores its quantity as a 32-bit integer (data model §4), which every quantity within
     * this bound fits.
     */
    public static final long MAX_QTY = 1_000_000L;

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
            // Each line is checked before it is merged, so a larger line of the same SKU cannot hide a line below
            // one. Both terms of a sum are then within the bound, so the sum cannot overflow before it is checked.
            requireWithinBound(line.sku(), line.qty());
            qtyBySku.merge(line.sku(), line.qty(),
                    (merged, added) -> requireWithinBound(line.sku(), merged + added));
        }
        return new Cart(qtyBySku.entrySet().stream()
                .map(entry -> new CartLine(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static long requireWithinBound(String sku, long qty) {
        if (qty < 1 || qty > MAX_QTY) {
            throw new CartRejectedException(Code.INVALID_QUANTITY,
                    "SKU '" + sku + "' has a quantity outside 1 to " + MAX_QTY + ": " + qty);
        }
        return qty;
    }
}
