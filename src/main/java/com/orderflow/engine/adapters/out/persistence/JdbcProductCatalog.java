package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.quoting.ProductCatalog;
import com.orderflow.engine.quoting.ProductView.Item;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the products of a tenant with explicit SQL, for the product list. A list price is read at the scale of the
 * tenant's currency, as the pricing snapshot reads it, so the list shows the price a new quote starts from. The rows
 * are read in no order: the product list sorts them in the SKU order of BR-00, which no database collation decides.
 */
@Repository
public class JdbcProductCatalog implements ProductCatalog {

    private static final String PRODUCTS = """
            SELECT sku, name, list_price, tax_rate
            FROM products
            WHERE tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;

    public JdbcProductCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Item> products(String tenantId, CurrencyPolicy currency) {
        return jdbc.sql(PRODUCTS)
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    String sku = rs.getString("sku");
                    return new Item(sku, rs.getString("name"),
                            MoneyColumns.read(rs.getBigDecimal("list_price"), currency,
                                    "List price of SKU '" + sku + "' of tenant '" + tenantId + "'"),
                            rs.getBigDecimal("tax_rate"));
                })
                .list();
    }
}
