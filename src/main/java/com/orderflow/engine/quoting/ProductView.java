package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a tenant sells, as the product list shows it (api-contract E-07): the ISO 4217 code of the tenant's currency,
 * given once because every list price is in it, then each product in the SKU order of BR-00, the order of the paid
 * lines of a breakdown. The order is set here, so no source of products, and no database collation, decides it.
 */
public record ProductView(String currency, List<Item> items) {

    /** The SKU order of BR-00: the natural order of the SKU codes, so "BEV-COLA-1500" comes before "BEV-COLA-350". */
    private static final Comparator<Item> SKU_ORDER = Comparator.comparing(Item::sku);

    public ProductView {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(items, "items");
        for (Item item : items) {
            String itemCurrency = item.listPrice().policy().currencyCode();
            if (!itemCurrency.equals(currency)) {
                throw new IllegalArgumentException("List price of SKU '" + item.sku() + "' is in " + itemCurrency
                        + ", not in the list currency " + currency);
            }
        }
        items = items.stream().sorted(SKU_ORDER).toList();
    }

    /**
     * One product: its SKU; its name, which a breakdown line also shows as its description; its list price before
     * levers and taxes, at the currency scale; and its tax rate. The list price is informative: only a quote prices a
     * cart (E-01).
     */
    public record Item(String sku, String description, Money listPrice, BigDecimal taxRate) {

        public Item {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(listPrice, "listPrice");
            Objects.requireNonNull(taxRate, "taxRate");
        }
    }
}
