package com.orderflow.engine.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Money columns are numeric(19,4) for every currency, so a database CHECK cannot know the scale of the tenant's
 * currency without a trigger (data model DM-01). The seeded amounts are therefore validated here, against the scale
 * that the tenant's country currency allows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class PriceScaleValidationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** Every tenant amount that needs more decimals than the scale of the tenant's currency. */
    private static final String AMOUNTS_ABOVE_CURRENCY_SCALE = """
            SELECT a.tenant_id || ' ' || a.source || ' ' || a.amount
            FROM (
                SELECT tenant_id, 'products.list_price ' || sku AS source, list_price AS amount FROM products
                UNION ALL
                SELECT tenant_id, 'combo_rules.combo_price ' || id, combo_price FROM combo_rules
                UNION ALL
                SELECT tenant_id, 'gift_rules.min_gross ' || id, min_gross FROM gift_rules WHERE min_gross IS NOT NULL
                UNION ALL
                SELECT tenant_id, 'credit_accounts.credit_limit ' || customer_id, credit_limit FROM credit_accounts
            ) a
            JOIN tenants t ON t.id = a.tenant_id
            JOIN countries c ON c.code = t.country_code
            JOIN currencies cur ON cur.code = c.currency_code
            WHERE a.amount <> round(a.amount, cur.scale)
            ORDER BY 1
            """;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everySeededAmountFitsTheScaleOfItsCurrency() {
        assertThat(jdbc.sql("SELECT DISTINCT tenant_id FROM products ORDER BY tenant_id").query(String.class).list())
                .as("tenants with seeded prices")
                .containsExactly("co-alpha", "ec-alpha");

        assertThat(amountsAboveCurrencyScale()).as("seeded amounts above their currency scale").isEmpty();
    }

    @Test
    void anAmountWithMoreDecimalsThanItsCurrencyIsReported() {
        insertProduct("co-alpha", "SCALE-COP-HALF", "2500.5");
        insertProduct("ec-alpha", "SCALE-USD-CENTS", "0.61");
        insertProduct("ec-alpha", "SCALE-USD-MILLS", "0.605");

        assertThat(amountsAboveCurrencyScale())
                .as("amounts above their currency scale")
                .containsExactly(
                        "co-alpha products.list_price SCALE-COP-HALF 2500.5000",
                        "ec-alpha products.list_price SCALE-USD-MILLS 0.6050");
    }

    private List<String> amountsAboveCurrencyScale() {
        return jdbc.sql(AMOUNTS_ABOVE_CURRENCY_SCALE).query(String.class).list();
    }

    private void insertProduct(String tenant, String sku, String listPrice) {
        String family = "family-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO families (tenant_id, code) VALUES (?, ?)")
                .params(tenant, family)
                .update();
        jdbc.sql("""
                        INSERT INTO products (tenant_id, id, sku, name, family_code, tax_rate, list_price)
                        VALUES (?, ?, ?, 'Scale check', ?, 0.19, ?)""")
                .params(tenant, UUID.randomUUID(), sku, family, new BigDecimal(listPrice))
                .update();
    }
}
