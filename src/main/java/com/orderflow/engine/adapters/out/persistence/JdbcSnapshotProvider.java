package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.quoting.SnapshotProvider;
import com.orderflow.engine.quoting.TenantPolicy;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the pricing snapshot of a quote with explicit SQL. Scale and rounding come from the currency and country rows,
 * never from {@code java.util.Currency} (SPEC §4).
 */
@Repository
public class JdbcSnapshotProvider implements SnapshotProvider {

    /** The quote TTL is an interval, read as whole microseconds, the precision of a PostgreSQL interval. */
    private static final String TENANT_POLICY = """
            SELECT c.currency_code, cur.scale, c.rounding_mode,
                   CAST(EXTRACT(EPOCH FROM c.quote_ttl) * 1000000 AS bigint) AS quote_ttl_micros
            FROM tenants t
            JOIN countries c ON c.code = t.country_code
            JOIN currencies cur ON cur.code = c.currency_code
            WHERE t.id = :tenantId
            """;

    private static final String CUSTOMER_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM customers WHERE tenant_id = :tenantId AND id = :customerId)
            """;

    /**
     * The SKUs are bound as one array, so the statement keeps two parameters whatever the size of the cart. PostgreSQL
     * refuses a statement with more than 65,535 parameters, which an IN list of one parameter per SKU would reach.
     */
    private static final String PRODUCTS = """
            SELECT sku, family_code, list_price, tax_rate
            FROM products
            WHERE tenant_id = :tenantId AND sku = ANY(:skus)
            """;

    private final JdbcClient jdbc;

    public JdbcSnapshotProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantPolicy> tenantPolicy(String tenantId) {
        return jdbc.sql(TENANT_POLICY)
                .param("tenantId", tenantId)
                .query((rs, row) -> new TenantPolicy(
                        new CurrencyPolicy(rs.getString("currency_code"), rs.getInt("scale"),
                                RoundingMode.valueOf(rs.getString("rounding_mode"))),
                        Duration.of(rs.getLong("quote_ttl_micros"), ChronoUnit.MICROS)))
                .optional();
    }

    @Override
    public boolean customerExists(String tenantId, UUID customerId) {
        return jdbc.sql(CUSTOMER_EXISTS)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public PricingSnapshot pricingSnapshot(String tenantId, CurrencyPolicy currency, Set<String> skus) {
        return new PricingSnapshot(currency, jdbc.sql(PRODUCTS)
                .param("tenantId", tenantId)
                .param("skus", skus.toArray(String[]::new))
                .query((rs, row) -> {
                    String sku = rs.getString("sku");
                    return new Product(sku, rs.getString("family_code"),
                            MoneyColumns.read(rs.getBigDecimal("list_price"), currency,
                                    "List price of SKU '" + sku + "' of tenant '" + tenantId + "'"),
                            rs.getBigDecimal("tax_rate"));
                })
                .list(), List.of(), List.of());
    }
}
