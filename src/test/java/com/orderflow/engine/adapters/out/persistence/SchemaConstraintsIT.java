package com.orderflow.engine.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The schema holds the tenant, order and credit invariants by itself, so wrong application code cannot break them.
 * Every reference between tenant-scoped tables includes tenant_id, so each one is shown rejecting a row of another
 * tenant after accepting the same row within the tenant. Every test runs in a transaction that is rolled back: the
 * seeded data is never changed, and a test that expects a violation ends with the violating statement, because
 * PostgreSQL aborts the transaction after it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class SchemaConstraintsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final String CO = "co-alpha";
    private static final String EC = "ec-alpha";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aProductCannotBelongToAFamilyOfAnotherTenant() {
        String family = insertFamily(CO);
        insertProduct(CO, family);

        assertViolates("fk_products_family", () -> insertProduct(EC, family));
    }

    @Test
    void aTierRuleCannotTargetAProductOfAnotherTenant() {
        UUID product = insertProduct(CO, insertFamily(CO));
        insertProductTierRule(CO, product);

        assertViolates("fk_tier_rules_product", () -> insertProductTierRule(EC, product));
    }

    @Test
    void aTierRuleCannotTargetAFamilyOfAnotherTenant() {
        String family = insertFamily(CO);
        insertFamilyTierRule(CO, family);

        assertViolates("fk_tier_rules_family", () -> insertFamilyTierRule(EC, family));
    }

    @Test
    void aTierBracketCannotBelongToATierRuleOfAnotherTenant() {
        UUID rule = insertFamilyTierRule(CO, insertFamily(CO));
        insertTierBracket(CO, rule);

        assertViolates("fk_tier_brackets_rule", () -> insertTierBracket(EC, rule));
    }

    @Test
    void aComboItemCannotBelongToAComboRuleOfAnotherTenant() {
        UUID rule = insertComboRule(CO);
        insertComboItem(CO, rule, insertProduct(CO, insertFamily(CO)));
        UUID foreignProduct = insertProduct(EC, insertFamily(EC));

        assertViolates("fk_combo_items_rule", () -> insertComboItem(EC, rule, foreignProduct));
    }

    @Test
    void aComboItemCannotNameAProductOfAnotherTenant() {
        UUID product = insertProduct(CO, insertFamily(CO));
        insertComboItem(CO, insertComboRule(CO), product);
        UUID foreignRule = insertComboRule(EC);

        assertViolates("fk_combo_items_product", () -> insertComboItem(EC, foreignRule, product));
    }

    @Test
    void aGiftRuleCannotBeTriggeredByAProductOfAnotherTenant() {
        UUID product = insertProduct(CO, insertFamily(CO));
        insertProductGiftRule(CO, product, product);
        UUID foreignGift = insertProduct(EC, insertFamily(EC));

        assertViolates("fk_gift_rules_trigger_product", () -> insertProductGiftRule(EC, product, foreignGift));
    }

    @Test
    void aGiftRuleCannotBeTriggeredByAFamilyOfAnotherTenant() {
        String family = insertFamily(CO);
        insertFamilyGiftRule(CO, family, insertProduct(CO, family));
        UUID foreignGift = insertProduct(EC, insertFamily(EC));

        assertViolates("fk_gift_rules_trigger_family", () -> insertFamilyGiftRule(EC, family, foreignGift));
    }

    @Test
    void aGiftRuleCannotGiveAProductOfAnotherTenant() {
        UUID gift = insertProduct(CO, insertFamily(CO));
        insertGrossGiftRule(CO, gift);

        assertViolates("fk_gift_rules_gift_product", () -> insertGrossGiftRule(EC, gift));
    }

    @Test
    void aQuoteCannotBelongToACustomerOfAnotherTenant() {
        UUID customer = insertCustomer(CO);
        insertQuote(CO, customer);

        assertViolates("fk_quotes_customer", () -> insertQuote(EC, customer));
    }

    @Test
    void aQuoteLineCannotBelongToAQuoteOfAnotherTenant() {
        UUID quote = insertQuote(CO, insertCustomer(CO));
        insertQuoteLine(CO, quote, insertProduct(CO, insertFamily(CO)));
        UUID foreignProduct = insertProduct(EC, insertFamily(EC));

        assertViolates("fk_quote_lines_quote", () -> insertQuoteLine(EC, quote, foreignProduct));
    }

    @Test
    void aQuoteLineCannotPriceAProductOfAnotherTenant() {
        UUID product = insertProduct(CO, insertFamily(CO));
        insertQuoteLine(CO, insertQuote(CO, insertCustomer(CO)), product);
        UUID foreignQuote = insertQuote(EC, insertCustomer(EC));

        assertViolates("fk_quote_lines_product", () -> insertQuoteLine(EC, foreignQuote, product));
    }

    @Test
    void aGiftLineCannotComeFromAGiftRuleOfAnotherTenant() {
        UUID product = insertProduct(CO, insertFamily(CO));
        UUID rule = insertGrossGiftRule(CO, product);
        insertGiftLine(CO, insertQuote(CO, insertCustomer(CO)), product, rule);
        UUID foreignQuote = insertQuote(EC, insertCustomer(EC));
        UUID foreignProduct = insertProduct(EC, insertFamily(EC));

        assertViolates("fk_quote_lines_gift_rule", () -> insertGiftLine(EC, foreignQuote, foreignProduct, rule));
    }

    @Test
    void aDiscountComponentCannotBelongToALineOfAnotherTenant() {
        UUID quote = insertQuote(CO, insertCustomer(CO));
        insertQuoteLine(CO, quote, insertProduct(CO, insertFamily(CO)));
        insertTierAdjustment(CO, quote);

        assertViolates("fk_quote_line_adjustments_line", () -> insertTierAdjustment(EC, quote));
    }

    @Test
    void anOrderCannotConfirmAQuoteOfAnotherTenant() {
        UUID customer = insertCustomer(CO);
        UUID quote = insertQuote(CO, customer);
        insertOrder(CO, quote, customer);

        assertViolates("fk_orders_quote", () -> insertOrder(EC, quote, customer));
    }

    @Test
    void aQuoteIsConfirmedByOneOrderAtMost() {
        UUID customer = insertCustomer(CO);
        UUID quote = insertQuote(CO, customer);
        insertOrder(CO, quote, customer);

        assertViolates("uq_orders_quote", () -> insertOrder(CO, quote, customer));
    }

    @Test
    void theOrderCustomerIsPinnedToTheQuoteCustomer() {
        UUID owner = insertCustomer(CO);
        UUID otherCustomer = insertCustomer(CO);
        insertOrder(CO, insertQuote(CO, otherCustomer), otherCustomer);
        UUID quote = insertQuote(CO, owner);

        assertViolates("fk_orders_quote", () -> insertOrder(CO, quote, otherCustomer));
    }

    @Test
    void anOrderStoresNoAmountOnlyItsNumberAndItsQuote() {
        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'orders'
                        ORDER BY ordinal_position""")
                        .query(String.class)
                        .list())
                .as("columns of orders")
                .containsExactly("tenant_id", "id", "quote_id", "customer_id", "order_number", "confirmed_at");
    }

    @Test
    void aCreditAccountCannotBelongToACustomerOfAnotherTenant() {
        UUID customer = insertCustomer(CO);
        insertCreditAccount(CO, customer, new BigDecimal("500000"));

        assertViolates("fk_credit_accounts_customer", () -> insertCreditAccount(EC, customer, new BigDecimal("1500")));
    }

    @Test
    void creditConsumptionMayReachTheLimitButNeverExceedIt() {
        UUID customer = insertCustomer(CO);
        insertCreditAccount(CO, customer, new BigDecimal("500000"));
        setConsumed(CO, customer, new BigDecimal("500000"));

        assertViolates("ck_credit_accounts_consumed", () -> setConsumed(CO, customer, new BigDecimal("500001")));
    }

    @Test
    void creditConsumptionIsNeverNegative() {
        UUID customer = insertCustomer(CO);
        insertCreditAccount(CO, customer, new BigDecimal("500000"));

        assertViolates("ck_credit_accounts_consumed", () -> setConsumed(CO, customer, new BigDecimal("-1")));
    }

    @Test
    void aCreditMovementCannotBelongToAnOrderOfAnotherTenant() {
        UUID order = insertConfirmedOrder(CO);
        insertCreditMovement(CO, order);

        assertViolates("fk_credit_ledger_order", () -> insertCreditMovement(EC, order));
    }

    @Test
    void anIdempotencyKeyCannotPointAtAnOrderOfAnotherTenant() {
        UUID order = insertConfirmedOrder(CO);
        insertIdempotencyKey(CO, insertCustomer(CO), order);
        UUID foreignCustomer = insertCustomer(EC);

        assertViolates("fk_idempotency_keys_order", () -> insertIdempotencyKey(EC, foreignCustomer, order));
    }

    @Test
    void anOutboxEventCannotPointAtAnOrderOfAnotherTenant() {
        UUID order = insertConfirmedOrder(CO);
        insertOutboxEvent(CO, order);

        assertViolates("fk_outbox_events_order", () -> insertOutboxEvent(EC, order));
    }

    @Test
    void aConfirmedQuoteCannotBeDeleted() {
        UUID customer = insertCustomer(CO);
        UUID quote = insertQuote(CO, customer);
        insertOrder(CO, quote, customer);

        assertViolates("fk_orders_quote", () -> deleteQuote(CO, quote));
    }

    @Test
    void anUnconfirmedQuoteIsDeletedWithItsLinesAndAdjustments() {
        UUID quote = insertQuote(CO, insertCustomer(CO));
        insertQuoteLine(CO, quote, insertProduct(CO, insertFamily(CO)));
        insertTierAdjustment(CO, quote);

        assertThat(deleteQuote(CO, quote)).as("deleted quotes").isEqualTo(1);
        assertThat(rowsOfQuote("quote_lines", CO, quote)).as("quote lines left").isZero();
        assertThat(rowsOfQuote("quote_line_adjustments", CO, quote)).as("adjustments left").isZero();
    }

    private static void assertViolates(String constraint, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataIntegrityViolationException.class)
                .extracting(SchemaConstraintsIT::violatedConstraint)
                .isEqualTo(constraint);
    }

    private static String violatedConstraint(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql.getServerErrorMessage().getConstraint();
            }
        }
        throw new AssertionError("No PostgreSQL error in the cause chain", error);
    }

    private String insertFamily(String tenant) {
        String code = "family-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO families (tenant_id, code) VALUES (?, ?)")
                .params(tenant, code)
                .update();
        return code;
    }

    private UUID insertProduct(String tenant, String family) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO products (tenant_id, id, sku, name, family_code, tax_rate, list_price)
                        VALUES (?, ?, ?, 'Test product', ?, 0.19, 1000)""")
                .params(tenant, id, "SKU-" + id, family)
                .update();
        return id;
    }

    private UUID insertProductTierRule(String tenant, UUID product) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tier_rules (tenant_id, id, product_id, active) VALUES (?, ?, ?, true)")
                .params(tenant, id, product)
                .update();
        return id;
    }

    private UUID insertFamilyTierRule(String tenant, String family) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tier_rules (tenant_id, id, family_code, active) VALUES (?, ?, ?, true)")
                .params(tenant, id, family)
                .update();
        return id;
    }

    private void insertTierBracket(String tenant, UUID rule) {
        jdbc.sql("INSERT INTO tier_brackets (tenant_id, tier_rule_id, from_qty, percent) VALUES (?, ?, 1, 0.05)")
                .params(tenant, rule)
                .update();
    }

    private UUID insertComboRule(String tenant) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO combo_rules (tenant_id, id, combo_price, active) VALUES (?, ?, 5000, true)")
                .params(tenant, id)
                .update();
        return id;
    }

    private void insertComboItem(String tenant, UUID rule, UUID product) {
        jdbc.sql("INSERT INTO combo_items (tenant_id, combo_rule_id, product_id, min_qty) VALUES (?, ?, ?, 1)")
                .params(tenant, rule, product)
                .update();
    }

    private void insertProductGiftRule(String tenant, UUID trigger, UUID gift) {
        jdbc.sql("""
                        INSERT INTO gift_rules
                            (tenant_id, id, trigger_product_id, min_qty, gift_product_id, gift_qty, repeatable, active)
                        VALUES (?, ?, ?, 10, ?, 1, true, true)""")
                .params(tenant, UUID.randomUUID(), trigger, gift)
                .update();
    }

    private void insertFamilyGiftRule(String tenant, String trigger, UUID gift) {
        jdbc.sql("""
                        INSERT INTO gift_rules
                            (tenant_id, id, trigger_family_code, min_qty, gift_product_id, gift_qty, repeatable, active)
                        VALUES (?, ?, ?, 10, ?, 1, true, true)""")
                .params(tenant, UUID.randomUUID(), trigger, gift)
                .update();
    }

    private UUID insertGrossGiftRule(String tenant, UUID gift) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO gift_rules (tenant_id, id, min_gross, gift_product_id, gift_qty, repeatable, active)
                        VALUES (?, ?, 50000, ?, 1, false, true)""")
                .params(tenant, id, gift)
                .update();
        return id;
    }

    private UUID insertCustomer(String tenant) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customers (tenant_id, id, name) VALUES (?, ?, 'Test customer')")
                .params(tenant, id)
                .update();
        return id;
    }

    private void insertCreditAccount(String tenant, UUID customer, BigDecimal limit) {
        jdbc.sql("INSERT INTO credit_accounts (tenant_id, customer_id, credit_limit, consumed) VALUES (?, ?, ?, 0)")
                .params(tenant, customer, limit)
                .update();
    }

    private void setConsumed(String tenant, UUID customer, BigDecimal consumed) {
        jdbc.sql("UPDATE credit_accounts SET consumed = ? WHERE tenant_id = ? AND customer_id = ?")
                .params(consumed, tenant, customer)
                .update();
    }

    private void insertCreditMovement(String tenant, UUID order) {
        jdbc.sql("INSERT INTO credit_ledger (tenant_id, order_id, amount) VALUES (?, ?, 1000)")
                .params(tenant, order)
                .update();
    }

    private UUID insertQuote(String tenant, UUID customer) {
        String currency = jdbc.sql("""
                        SELECT c.currency_code FROM tenants t JOIN countries c ON c.code = t.country_code
                        WHERE t.id = ?""")
                .param(tenant)
                .query(String.class)
                .single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quotes (tenant_id, id, customer_id, currency_code, payment_method, created_at, expires_at)
                        VALUES (?, ?, ?, ?, 'CASH', ?, ?)""")
                .params(tenant, id, customer, currency, NOW, NOW.plusMinutes(15))
                .update();
        return id;
    }

    private void insertQuoteLine(String tenant, UUID quote, UUID product) {
        jdbc.sql("""
                        INSERT INTO quote_lines
                            (tenant_id, quote_id, line_no, product_id, qty, unit_price, tax_rate, taxable_base, tax)
                        VALUES (?, ?, 1, ?, 1, 1000, 0.19, 1000, 190)""")
                .params(tenant, quote, product)
                .update();
    }

    private void insertGiftLine(String tenant, UUID quote, UUID product, UUID giftRule) {
        jdbc.sql("""
                        INSERT INTO quote_lines
                            (tenant_id, quote_id, line_no, product_id, gift_rule_id, qty, unit_price, tax_rate,
                             taxable_base, tax)
                        VALUES (?, ?, 1, ?, ?, 1, 1000, 0.19, 0, 0)""")
                .params(tenant, quote, product, giftRule)
                .update();
    }

    private void insertTierAdjustment(String tenant, UUID quote) {
        jdbc.sql("""
                        INSERT INTO quote_line_adjustments (tenant_id, quote_id, line_no, kind, rule_id, amount)
                        VALUES (?, ?, 1, 'TIER', ?, 50)""")
                .params(tenant, quote, UUID.randomUUID())
                .update();
    }

    private UUID insertOrder(String tenant, UUID quote, UUID customer) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(tenant, id, quote, customer, "TEST-" + id, NOW)
                .update();
        return id;
    }

    private UUID insertConfirmedOrder(String tenant) {
        UUID customer = insertCustomer(tenant);
        return insertOrder(tenant, insertQuote(tenant, customer), customer);
    }

    private void insertIdempotencyKey(String tenant, UUID customer, UUID order) {
        jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (tenant_id, customer_id, idem_key, request_hash, order_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(tenant, customer, "key-" + UUID.randomUUID(), "hash".getBytes(StandardCharsets.US_ASCII),
                        order, NOW)
                .update();
    }

    private void insertOutboxEvent(String tenant, UUID order) {
        jdbc.sql("""
                        INSERT INTO outbox_events
                            (tenant_id, id, order_id, destination, status, attempts, next_attempt_at, created_at)
                        VALUES (?, ?, ?, 'PUSH', 'PENDING', 0, ?, ?)""")
                .params(tenant, UUID.randomUUID(), order, NOW, NOW)
                .update();
    }

    private int deleteQuote(String tenant, UUID quote) {
        return jdbc.sql("DELETE FROM quotes WHERE tenant_id = ? AND id = ?")
                .params(tenant, quote)
                .update();
    }

    private long rowsOfQuote(String table, String tenant, UUID quote) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND quote_id = ?")
                .params(tenant, quote)
                .query(Long.class)
                .single();
    }
}
