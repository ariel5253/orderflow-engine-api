package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.quoting.ProductView;
import java.util.List;

/**
 * The body of the product list (api-contract E-07), with its fields in the order of the contract: the ISO 4217 code of
 * the tenant's currency, given once for every list price, then the products in SKU order. A list price is a JSON
 * string at the currency scale and a tax rate a JSON string as stored, never JSON numbers. No lever, family, product
 * id or credit data is part of it.
 */
public record ProductListResponse(String currency, List<Item> items) {

    public static ProductListResponse from(ProductView products) {
        return new ProductListResponse(products.currency(), products.items().stream().map(Item::from).toList());
    }

    /** One product. Its description is the product name, as in a breakdown line. */
    public record Item(String sku, String description, String listPrice, String taxRate) {

        static Item from(ProductView.Item product) {
            return new Item(product.sku(), product.description(), QuoteResponse.money(product.listPrice()),
                    product.taxRate().toPlainString());
        }
    }
}
