package com.orderflow.engine.adapters.out.persistence;

import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_COMBO_RULE;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_GIFT_RULE;
import static com.orderflow.engine.quoting.QuoteTestData.CO_TIER_RULE;
import static com.orderflow.engine.quoting.QuoteTestData.EC;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.Cart;
import com.orderflow.engine.pricing.domain.CartLine;
import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.GiftRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import com.orderflow.engine.quoting.CreateQuoteUseCase;
import com.orderflow.engine.quoting.GetQuoteUseCase;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteTestData;
import com.orderflow.engine.quoting.QuoteView;
import com.orderflow.engine.quoting.SealedQuote;
import com.orderflow.engine.quoting.SnapshotProvider;
import com.orderflow.engine.quoting.StoredQuote;
import com.orderflow.engine.quoting.TenantPolicy;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-20 and data model DM-01, DM-02: a quote is sealed. It is stored with its lines, their discount components and its
 * expiry, the prices it was quoted with stay with it, it is never updated, and a stored price with more decimals than
 * its currency allows is rejected when it is loaded. The snapshot a quote is priced with holds the tenant's active
 * levers and nothing inactive. Every test runs in a transaction that is rolled back, except the one that proves the
 * quote is written in a transaction of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class QuotePersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);
    private static final UUID EC_TIER_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000001");
    private static final UUID EC_COMBO_RULE = UUID.fromString("01a13cdb-cc00-7002-8004-000000000001");
    private static final UUID EC_GIFT_RULE = UUID.fromString("01a13cdb-cc00-7002-8005-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2100-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2100-01-01T00:15:00Z");

    @Autowired
    private CreateQuoteUseCase createQuote;

    @Autowired
    private GetQuoteUseCase getQuote;

    @Autowired
    private QuoteRepository quotes;

    @Autowired
    private SnapshotProvider snapshots;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aQuoteIsStoredWithItsLinesThePricesItWasQuotedWithAndItsExpiry() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        QuoteView quote = createQuote.create(CO, CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT,
                cart(new CartLine("SNK-CHIPS-45", 2), new CartLine("BEV-COLA-1500", 3)));
        Instant after = Instant.now();

        assertThat(quote.quoteId().version()).as("the quote id is a UUIDv7").isEqualTo(7);
        assertThat(jdbc.sql("""
                        SELECT customer_id, currency_code, payment_method,
                               CAST(EXTRACT(EPOCH FROM expires_at - created_at) AS integer)
                        FROM quotes WHERE tenant_id = :tenantId AND id = :quoteId""")
                .param("tenantId", CO)
                .param("quoteId", quote.quoteId())
                .query((rs, row) -> tuple(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4)))
                .single())
                .as("customer, currency, payment method and TTL in seconds")
                .isEqualTo(tuple(CO_CREDIT_CUSTOMER, "COP", "CREDIT", 15 * 60));
        assertThat(jdbc.sql("SELECT created_at FROM quotes WHERE tenant_id = :tenantId AND id = :quoteId")
                .param("tenantId", CO)
                .param("quoteId", quote.quoteId())
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single())
                .isBetween(before, after);
        assertThat(jdbc.sql("""
                        SELECT l.line_no, p.sku, l.gift_rule_id, l.qty, CAST(l.unit_price AS text),
                               CAST(l.tax_rate AS text), CAST(l.taxable_base AS text), CAST(l.tax AS text)
                        FROM quote_lines l JOIN products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
                        WHERE l.tenant_id = :tenantId AND l.quote_id = :quoteId
                        ORDER BY l.line_no""")
                .param("tenantId", CO)
                .param("quoteId", quote.quoteId())
                .query((rs, row) -> tuple(rs.getInt(1), rs.getString(2), rs.getObject(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                .list())
                .as("lines in SKU order, with the unit price, rate, taxable base and tax of quote time")
                .containsExactly(
                        tuple(1, "BEV-COLA-1500", null, 3, "5200.0000", "0.190000", "15600.0000", "2964.0000"),
                        tuple(2, "SNK-CHIPS-45", null, 2, "1800.0000", "0.050000", "3600.0000", "180.0000"));
        assertThat(countAdjustments(quote.quoteId())).as("discount components of a cart with no lever").isZero();
    }

    @Test
    void discountComponentsAndGiftLinesAreStoredAndReadBackUnchanged() {
        SealedQuote sealed = QuoteTestData.withEveryComponent(CO_CASH_CUSTOMER, CREATED_AT, EXPIRES_AT);

        UUID quoteId = quotes.insert(sealed);

        StoredQuote stored = quotes.find(CO, CO_CASH_CUSTOMER, quoteId).orElseThrow();
        assertThat(stored.sealed()).isEqualTo(sealed);
        assertThat(stored.confirmed()).isFalse();
        assertThat(stored.descriptions()).isEqualTo(Map.of(
                "BEV-COLA-350", "Cola 350 ml", "SNK-CHIPS-45", "Chips 45 g", "BEV-WATER-600", "Water 600 ml"));
        assertThat(jdbc.sql("""
                        SELECT line_no, kind, CAST(amount AS text) FROM quote_line_adjustments
                        WHERE tenant_id = :tenantId AND quote_id = :quoteId ORDER BY line_no, kind""")
                .param("tenantId", CO)
                .param("quoteId", quoteId)
                .query((rs, row) -> tuple(rs.getInt(1), rs.getString(2), rs.getString(3)))
                .list())
                .containsExactly(
                        tuple(1, "COMBO", "13235.0000"),
                        tuple(1, "TIER", "125.0000"),
                        tuple(2, "COMBO", "4765.0000"));
    }

    @Test
    void storedQuotesAreNeverUpdated() {
        // DDL is transactional in PostgreSQL, so these guards disappear with the test's rollback.
        jdbc.sql("""
                CREATE FUNCTION reject_quote_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'table % is insert-only', TG_TABLE_NAME;
                END
                $$""").update();
        for (String table : List.of("quotes", "quote_lines", "quote_line_adjustments")) {
            jdbc.sql("CREATE TRIGGER seal_" + table + " BEFORE UPDATE ON " + table
                    + " FOR EACH STATEMENT EXECUTE FUNCTION reject_quote_update()").update();
        }

        QuoteView created = createQuote.create(CO, CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT,
                cart(new CartLine("BEV-COLA-1500", 3)));
        getQuote.get(CO, CO_CREDIT_CUSTOMER, created.quoteId());
        UUID withComponents = quotes.insert(QuoteTestData.withEveryComponent(CO_CASH_CUSTOMER, CREATED_AT,
                EXPIRES_AT));
        quotes.find(CO, CO_CASH_CUSTOMER, withComponents).orElseThrow();

        // The flows above ran under the guards: any UPDATE statement, even one that matches no row, is rejected.
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> jdbc.sql("UPDATE quote_lines SET qty = qty WHERE false").update())
                .withMessageContaining("table quote_lines is insert-only");
    }

    @Test
    void aLaterPriceChangeReachesNewQuotesButNeverAStoredOne() {
        QuoteView quoted = createQuote.create(CO, CO_CASH_CUSTOMER, PaymentMethod.CASH,
                cart(new CartLine("BEV-COLA-1500", 3)));

        jdbc.sql("UPDATE products SET list_price = 6000 WHERE tenant_id = :tenantId AND sku = 'BEV-COLA-1500'")
                .param("tenantId", CO)
                .update();

        StoredQuote stored = quotes.find(CO, CO_CASH_CUSTOMER, quoted.quoteId()).orElseThrow();
        assertThat(stored.sealed().lines().getFirst().unitPrice()).isEqualTo(QuoteTestData.cop("5200"));
        QuoteView requoted = createQuote.create(CO, CO_CASH_CUSTOMER, PaymentMethod.CASH,
                cart(new CartLine("BEV-COLA-1500", 3)));
        assertThat(requoted.lines().getFirst().unitPrice()).isEqualTo(QuoteTestData.cop("6000"));
    }

    @Test
    void aStoredPriceIsReadAtTheScaleOfItsCurrency() {
        PricingSnapshot snapshot = snapshotOf(EC, "BEV-COLA-350", "BEV-COLA-1500");

        assertThat(snapshot.product("BEV-COLA-350").orElseThrow().listPrice().amount().toPlainString())
                .isEqualTo("0.60");
        assertThat(snapshot.product("BEV-COLA-1500").orElseThrow().listPrice().amount().toPlainString())
                .isEqualTo("1.45");
    }

    @Test
    void aPriceWithMoreDecimalsThanItsCurrencyIsRejectedWhenLoaded() {
        jdbc.sql("UPDATE products SET list_price = 2500.5 WHERE tenant_id = :tenantId AND sku = 'BEV-COLA-350'")
                .param("tenantId", CO)
                .update();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> snapshotOf(CO, "BEV-COLA-350"))
                .withMessage("List price of SKU 'BEV-COLA-350' of tenant 'co-alpha' 2500.5000 has more decimals"
                        + " than the COP scale 0");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> createQuote.create(CO, CO_CASH_CUSTOMER, PaymentMethod.CASH,
                        cart(new CartLine("BEV-COLA-350", 1))));
        assertThat(jdbc.sql("SELECT count(*) FROM quotes").query(Long.class).single()).isZero();
    }

    /**
     * The snapshot holds the tenant's active levers in the shape the engine reads, one of each seeded shape: a SKU
     * tier rule, a combo and a family gift in co-alpha, and a family tier rule, a combo and a gross subtotal gift in
     * ec-alpha, with each country's gift tax policy. The product of each active gift rule is read with the cart's,
     * because the engine prices a gift line at its list price; a combo item or a tier target outside the cart is not.
     */
    @Test
    void theSnapshotHoldsTheActiveLeversOfTheTenant() {
        PricingSnapshot co = snapshotOf(CO, "SNK-CHIPS-45");
        PricingSnapshot ec = snapshotOf(EC, "BEV-COLA-350");

        assertThat(co.giftTaxPolicy()).isEqualTo(GiftTaxPolicy.TAXED);
        assertThat(co.tierRules()).containsExactly(new TierRule(CO_TIER_RULE, Scope.SKU, "BEV-COLA-350", List.of(
                new Bracket(1, new BigDecimal("0.000000")),
                new Bracket(10, new BigDecimal("0.050000")),
                new Bracket(20, new BigDecimal("0.100000")))));
        assertThat(co.comboRules()).containsExactly(new ComboRule(CO_COMBO_RULE, QuoteTestData.cop("5000"),
                List.of(new Item("BEV-COLA-350", 2), new Item("SNK-CHIPS-45", 1))));
        assertThat(co.giftRules()).containsExactly(new GiftRule(CO_GIFT_RULE, new FamilyQuantity("snacks", 10),
                "BEV-WATER-600", 1, true));
        assertThat(co.product("BEV-WATER-600")).contains(new Product("BEV-WATER-600", "waters",
                QuoteTestData.cop("1500"), new BigDecimal("0.190000")));
        assertThat(co.product("SNK-CHIPS-45")).isPresent();
        assertThat(co.product("BEV-COLA-350")).as("a combo item and tier target outside the cart").isEmpty();

        assertThat(ec.giftTaxPolicy()).isEqualTo(GiftTaxPolicy.EXEMPT);
        assertThat(ec.tierRules()).containsExactly(new TierRule(EC_TIER_RULE, Scope.FAMILY, "colas", List.of(
                new Bracket(1, new BigDecimal("0.000000")),
                new Bracket(12, new BigDecimal("0.040000")),
                new Bracket(24, new BigDecimal("0.080000")))));
        assertThat(ec.comboRules()).containsExactly(new ComboRule(EC_COMBO_RULE, usd("3.99"),
                List.of(new Item("BEV-COLA-350", 6), new Item("SNK-PLANTAIN-45", 2))));
        assertThat(ec.giftRules()).containsExactly(new GiftRule(EC_GIFT_RULE, new GrossSubtotal(usd("50.00")),
                "BEV-WATER-600", 2, false));
        assertThat(ec.product("BEV-WATER-600")).isPresent();
    }

    /** No seeded gift rule is triggered by one SKU, so this test adds one: it is read with the SKU it counts. */
    @Test
    void aGiftRuleTriggeredByOneSkuIsReadWithThatSku() {
        UUID rule = UUID.fromString("01a13cdb-cc00-7001-8005-000000000099");
        jdbc.sql("""
                        INSERT INTO gift_rules
                            (tenant_id, id, trigger_product_id, trigger_family_code, min_qty, min_gross,
                             gift_product_id, gift_qty, repeatable, active)
                        SELECT :tenantId, :ruleId, t.id, NULL, 6, NULL, g.id, 1, false, true
                        FROM products t, products g
                        WHERE t.tenant_id = :tenantId AND t.sku = 'BEV-COLA-1500'
                          AND g.tenant_id = :tenantId AND g.sku = 'SNK-CHIPS-45'""")
                .param("tenantId", CO)
                .param("ruleId", rule)
                .update();

        PricingSnapshot co = snapshotOf(CO, "BEV-COLA-1500");

        assertThat(co.giftRules()).containsExactly(
                new GiftRule(CO_GIFT_RULE, new FamilyQuantity("snacks", 10), "BEV-WATER-600", 1, true),
                new GiftRule(rule, new SkuQuantity("BEV-COLA-1500", 6), "SNK-CHIPS-45", 1, false));
        assertThat(co.product("SNK-CHIPS-45")).as("the product the added rule gives").isPresent();
    }

    /** A deactivated lever is never read, and neither is the product of a deactivated gift rule. */
    @Test
    void anInactiveLeverIsNeverRead() {
        for (String table : List.of("tier_rules", "combo_rules", "gift_rules")) {
            jdbc.sql("UPDATE " + table + " SET active = false WHERE tenant_id = :tenantId")
                    .param("tenantId", CO)
                    .update();
        }

        PricingSnapshot co = snapshotOf(CO, "BEV-COLA-350", "SNK-CHIPS-45");

        assertThat(co.tierRules()).isEmpty();
        assertThat(co.comboRules()).isEmpty();
        assertThat(co.giftRules()).isEmpty();
        assertThat(co.product("BEV-WATER-600")).as("the product of an inactive gift rule").isEmpty();
        assertThat(snapshotOf(EC, "BEV-COLA-350").tierRules()).as("the levers of another tenant").hasSize(1);
    }

    /**
     * Each statement of a READ COMMITTED transaction sees the rows committed when it starts, so a gift rule can be
     * activated between two statements of the same snapshot. This test activates the seeded gift rule of co-alpha
     * right after the first statement of the snapshot: the snapshot still holds the product the rule gives, because
     * that product is read in the same statement as the rule.
     */
    @Test
    void aGiftRuleActivatedWhileTheSnapshotIsReadComesWithItsProduct() {
        setGiftRulesActive(CO, false);
        TenantPolicy policy = snapshots.tenantPolicy(CO).orElseThrow();
        AtomicInteger statements = new AtomicInteger();
        JdbcClient activatingAfterTheFirstStatement = (JdbcClient) Proxy.newProxyInstance(
                JdbcClient.class.getClassLoader(), new Class<?>[] {JdbcClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sql") && statements.getAndIncrement() == 1) {
                        setGiftRulesActive(CO, true);
                    }
                    return method.invoke(jdbc, args);
                });

        PricingSnapshot co = new JdbcSnapshotProvider(activatingAfterTheFirstStatement)
                .pricingSnapshot(CO, policy, Set.of("SNK-CHIPS-45"));

        assertThat(statements).as("statements of the snapshot").hasValueGreaterThan(1);
        assertThat(co.giftRules()).extracting(GiftRule::id).containsExactly(CO_GIFT_RULE);
        assertThat(co.product("BEV-WATER-600")).as("the product the activated rule gives").isPresent();
    }

    /** A cart may hold the SKU a gift rule gives: the snapshot then holds that product once, as the tenant sells it. */
    @Test
    void aGiftProductTheCartAlsoHoldsIsReadOnce() {
        PricingSnapshot co = snapshotOf(CO, "BEV-WATER-600", "SNK-CHIPS-45");

        assertThat(co.giftRules()).extracting(GiftRule::giftSku).containsExactly("BEV-WATER-600");
        assertThat(co.product("BEV-WATER-600")).contains(new Product("BEV-WATER-600", "waters",
                QuoteTestData.cop("1500"), new BigDecimal("0.190000")));
        assertThat(co.product("SNK-CHIPS-45")).isPresent();
    }

    /** DM-01 holds for the amounts of a lever too: a combo price and a gross subtotal condition are money. */
    @Test
    void aLeverAmountWithMoreDecimalsThanItsCurrencyIsRejectedWhenLoaded() {
        jdbc.sql("UPDATE combo_rules SET combo_price = 5000.5 WHERE tenant_id = :tenantId")
                .param("tenantId", CO)
                .update();
        jdbc.sql("UPDATE gift_rules SET min_gross = 50.005 WHERE tenant_id = :tenantId")
                .param("tenantId", EC)
                .update();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> snapshotOf(CO, "SNK-CHIPS-45"))
                .withMessage("Combo price of combo rule " + CO_COMBO_RULE + " of tenant 'co-alpha' 5000.5000 has more"
                        + " decimals than the COP scale 0");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> snapshotOf(EC, "BEV-COLA-350"))
                .withMessage("Minimum gross subtotal of gift rule " + EC_GIFT_RULE + " of tenant 'ec-alpha' 50.0050"
                        + " has more decimals than the USD scale 2");
    }

    @Test
    void aQuoteIsFoundOnlyByItsTenantAndItsCustomer() {
        UUID quoteId = quotes.insert(QuoteTestData.threeColas(CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT, CREATED_AT,
                EXPIRES_AT));

        assertThat(quotes.find(CO, CO_CREDIT_CUSTOMER, quoteId)).isPresent();
        assertThat(quotes.find(CO, CO_CASH_CUSTOMER, quoteId)).as("another customer of the tenant").isEmpty();
        assertThat(quotes.find(EC, EC_CREDIT_CUSTOMER, quoteId)).as("a customer of another tenant").isEmpty();
        assertThat(quotes.find(EC, CO_CREDIT_CUSTOMER, quoteId)).as("the customer under another tenant").isEmpty();
        assertThat(quotes.find(CO, CO_CREDIT_CUSTOMER, UUID.randomUUID())).as("an unknown id").isEmpty();
    }

    @Test
    void aConfirmedQuoteIsReadAsConfirmed() {
        UUID quoteId = quotes.insert(QuoteTestData.threeColas(CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT, CREATED_AT,
                EXPIRES_AT));
        assertThat(quotes.find(CO, CO_CREDIT_CUSTOMER, quoteId).orElseThrow().confirmed()).isFalse();

        insertOrder(quoteId, CO_CREDIT_CUSTOMER);

        assertThat(quotes.find(CO, CO_CREDIT_CUSTOMER, quoteId).orElseThrow().confirmed()).isTrue();
    }

    /**
     * Runs outside the test transaction, so the use case commits or rolls back on its own. A guard rejects the second
     * line of any quote, so the quote fails after its header and first line were written: none of it may remain.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aQuoteIsWrittenWholeOrNotAtAll() {
        long quotesBefore = countQuotesOf(CO_CASH_CUSTOMER);
        jdbc.sql("""
                CREATE FUNCTION reject_second_line() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.line_no = 2 THEN
                        RAISE EXCEPTION 'second line rejected';
                    END IF;
                    RETURN NEW;
                END
                $$""").update();
        jdbc.sql("""
                CREATE TRIGGER reject_second_line BEFORE INSERT ON quote_lines
                FOR EACH ROW EXECUTE FUNCTION reject_second_line()""").update();
        try {
            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> createQuote.create(CO, CO_CASH_CUSTOMER, PaymentMethod.CASH,
                            cart(new CartLine("BEV-COLA-1500", 3), new CartLine("SNK-CHIPS-45", 2))))
                    .withMessageContaining("second line rejected");

            assertThat(countQuotesOf(CO_CASH_CUSTOMER)).isEqualTo(quotesBefore);
        } finally {
            jdbc.sql("DROP TRIGGER reject_second_line ON quote_lines").update();
            jdbc.sql("DROP FUNCTION reject_second_line()").update();
        }
    }

    /** The snapshot of a cart of these SKUs, under the policy of the tenant's country. */
    private PricingSnapshot snapshotOf(String tenantId, String... skus) {
        return snapshots.pricingSnapshot(tenantId, snapshots.tenantPolicy(tenantId).orElseThrow(), Set.of(skus));
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    private void setGiftRulesActive(String tenantId, boolean active) {
        jdbc.sql("UPDATE gift_rules SET active = :active WHERE tenant_id = :tenantId")
                .param("active", active)
                .param("tenantId", tenantId)
                .update();
    }

    private void insertOrder(UUID quoteId, UUID customerId) {
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (:tenantId, :orderId, :quoteId, :customerId, 'CO-000001', now())""")
                .param("tenantId", CO)
                .param("orderId", UUID.randomUUID())
                .param("quoteId", quoteId)
                .param("customerId", customerId)
                .update();
    }

    private long countQuotesOf(UUID customerId) {
        return jdbc.sql("SELECT count(*) FROM quotes WHERE tenant_id = :tenantId AND customer_id = :customerId")
                .param("tenantId", CO)
                .param("customerId", customerId)
                .query(Long.class)
                .single();
    }

    private long countAdjustments(UUID quoteId) {
        return jdbc.sql("SELECT count(*) FROM quote_line_adjustments WHERE tenant_id = :tenantId AND quote_id = :id")
                .param("tenantId", CO)
                .param("id", quoteId)
                .query(Long.class)
                .single();
    }

    private static Cart cart(CartLine... lines) {
        return new Cart(List.of(lines));
    }
}
