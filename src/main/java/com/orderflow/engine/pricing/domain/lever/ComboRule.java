package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.Money;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A combo rule of SPEC BR-03: a required set of SKUs, each with the units one application needs,
 * sold together at the combo price per application. The items are kept in SKU order.
 */
public record ComboRule(UUID id, Money comboPrice, List<Item> items) {

    public ComboRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(comboPrice, "comboPrice");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Combo rule " + id + " has no item");
        }
        Set<String> skus = new HashSet<>();
        for (Item item : items) {
            if (!skus.add(item.sku())) {
                throw new IllegalArgumentException("Combo rule " + id + " lists SKU " + item.sku() + " twice");
            }
        }
        if (comboPrice.amount().signum() < 0) {
            throw new IllegalArgumentException(
                    "Combo rule " + id + " has a negative price: " + comboPrice.amount());
        }
        items = items.stream().sorted(Comparator.comparing(Item::sku)).toList();
    }

    /** One SKU of the required set, and the units of it that one application consumes. */
    public record Item(String sku, long minQty) {

        public Item {
            Objects.requireNonNull(sku, "sku");
            if (minQty < 1) {
                throw new IllegalArgumentException(
                        "A combo item needs at least one unit of SKU " + sku + ": " + minQty);
            }
        }
    }
}
