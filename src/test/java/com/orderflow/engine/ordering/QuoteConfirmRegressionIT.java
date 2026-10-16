package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.CO_CART;
import static com.orderflow.engine.ordering.OrderTestSupport.CO_CART_LINES;
import static com.orderflow.engine.ordering.OrderTestSupport.CO_CART_TOTALS;
import static com.orderflow.engine.ordering.OrderTestSupport.CUSTOMER_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.QUOTES;
import static com.orderflow.engine.ordering.OrderTestSupport.TENANT_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.quoting.QuoteTestData;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * GT-06, AC-21 and P-02: the confirmed total is the quoted total. Between a quote and its confirmation the catalog
 * changes: the chips price rises from 1,800 to 1,900, and every lever of co-alpha (its tier rule, its combo and its
 * gift) is deactivated. The order still shows the sealed breakdown, the quote reads back unchanged, and a credit
 * order consumes the quoted total. A fresh quote of the same cart prices the changed catalog and differs, which proves
 * that each test can fail (SPEC section 6, BR-07). Every expected figure is worked out by hand in this class or in
 * {@link OrderTestSupport}. Confirmations commit for real, so each test restores the catalog and removes its orders
 * and quotes afterwards.
 */
@Tag("golden")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QuoteConfirmRegressionIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /**
     * {@link OrderTestSupport#CO_CART} after the change: three 1.5 l colas at 5,200, 15,600 plus 2,964 of tax, and two
     * chips at the new 1,900, 3,800 plus 190 of tax at 5%. Net 19,400 plus 3,154 of taxes is 22,554.
     */
    private static final String CO_CART_LINES_AFTER_THE_CHANGE = """
            [
              {"sku": "BEV-COLA-1500", "description": "Cola 1.5 l", "qty": 3, "isGift": false,
               "unitPrice": "5200", "gross": "15600", "discounts": [], "comboAllocations": [],
               "net": "15600", "taxableBase": "15600", "taxRate": "0.190000", "tax": "2964"},
              {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 2, "isGift": false,
               "unitPrice": "1900", "gross": "3800", "discounts": [], "comboAllocations": [],
               "net": "3800", "taxableBase": "3800", "taxRate": "0.050000", "tax": "190"}
            ]""";

    private static final String CO_CART_TOTALS_AFTER_THE_CHANGE = """
            {"grossSubtotal": "19400", "discounts": [], "gifts": [],
             "taxableBase": "19400", "taxes": "3154", "total": "22554"}""";

    /** The paid lines of {@link QuoteTestData#withEveryComponent}: 30 colas of 350 ml and 10 chips. */
    private static final String EVERY_COMPONENT_CART = """
            {"paymentMethod": "CASH",
             "lines": [{"sku": "BEV-COLA-350", "qty": 30}, {"sku": "SNK-CHIPS-45", "qty": 10}]}""";

    /**
     * {@link QuoteTestData#withEveryComponent} as an order renders it: ten combo applications allocated 13,235 to the
     * colas and 4,765 to the chips (18,000 in all), a 125 tier discount on the colas the combo leaves, and a taxed
     * gift water with a reference value of 1,500. Net 74,875 plus 12,659 of taxes is 87,534.
     */
    private static final String EVERY_COMPONENT_LINES = """
            [
              {"sku": "BEV-COLA-350", "description": "Cola 350 ml", "qty": 30, "isGift": false,
               "unitPrice": "2500", "gross": "75000",
               "discounts": [{"origin": "TIER:01a13cdb-cc00-7001-8003-000000000001", "amount": "125"}],
               "comboAllocations": [{"comboId": "01a13cdb-cc00-7001-8004-000000000001", "amount": "13235"}],
               "net": "61640", "taxableBase": "61640", "taxRate": "0.190000", "tax": "11712"},
              {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 10, "isGift": false,
               "unitPrice": "1800", "gross": "18000", "discounts": [],
               "comboAllocations": [{"comboId": "01a13cdb-cc00-7001-8004-000000000001", "amount": "4765"}],
               "net": "13235", "taxableBase": "13235", "taxRate": "0.050000", "tax": "662"},
              {"sku": "BEV-WATER-600", "description": "Water 600 ml", "qty": 1, "isGift": true,
               "origin": "GIFT:01a13cdb-cc00-7001-8005-000000000001",
               "unitPrice": "0", "gross": "0", "discounts": [], "comboAllocations": [],
               "net": "0", "referenceValue": "1500", "taxableBase": "1500", "taxRate": "0.190000", "tax": "285"}
            ]""";

    private static final String EVERY_COMPONENT_TOTALS = """
            {"grossSubtotal": "93000",
             "discounts": [{"origin": "COMBO:01a13cdb-cc00-7001-8004-000000000001", "amount": "18000"},
                           {"origin": "TIER:01a13cdb-cc00-7001-8003-000000000001", "amount": "125"}],
             "gifts": [{"sku": "BEV-WATER-600", "qty": 1, "origin": "GIFT:01a13cdb-cc00-7001-8005-000000000001"}],
             "taxableBase": "76375", "taxes": "12659", "total": "87534"}""";

    /**
     * The same paid cart priced on the changed catalog, where no lever is active: 30 colas at 2,500, 75,000 plus
     * 14,250 of tax at 19%, and 10 chips at 1,900, 19,000 plus 950 of tax at 5%. Net 94,000 plus 15,200 of taxes is
     * 109,200, and there is no gift line.
     */
    private static final String EVERY_COMPONENT_TOTALS_AFTER_THE_CHANGE = """
            {"grossSubtotal": "94000", "discounts": [], "gifts": [],
             "taxableBase": "94000", "taxes": "15200", "total": "109200"}""";

    private static final String[] LEVER_TABLES = {"tier_rules", "combo_rules", "gift_rules"};

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private OrderTestSupport orders;

    @BeforeEach
    void setUp() {
        orders = new OrderTestSupport(mvc, jdbc);
    }

    @AfterEach
    void restoreTheCatalogAndRemoveOrdersAndQuotes() {
        setChipsPrice(1800);
        setLeversActive(true);
        orders.deleteOrdersAndQuotes();
    }

    @Test
    void gt06AQuotedCartIsConfirmedAtItsQuotedTotalAfterThePriceAndTheLeversChange() {
        MvcTestResult quoted = orders.quote(CO, CO_CREDIT_CUSTOMER, CO_CART.formatted("CREDIT"));
        assertThat(quoted).hasStatus(HttpStatus.CREATED);
        assertThat(json(quoted).required("lines")).isEqualTo(json(CO_CART_LINES));
        assertThat(json(quoted).required("totals")).isEqualTo(json(CO_CART_TOTALS));
        UUID quoteId = UUID.fromString(json(quoted).required("quoteId").stringValue());

        changeTheCatalog();
        MvcTestResult confirmed = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId);

        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        JsonNode order = json(confirmed);
        assertThat(order.required("quoteId").stringValue()).isEqualTo(quoteId.toString());
        assertThat(order.required("currency").stringValue()).isEqualTo("COP");
        assertThat(order.required("lines")).as("confirmed lines").isEqualTo(json(CO_CART_LINES));
        assertThat(order.required("totals")).as("confirmed totals").isEqualTo(json(CO_CART_TOTALS));
        assertThat(orders.ledgerAmounts()).as("the credit movement is the quoted total").containsExactly("22344.0000");
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("22344.0000");
        assertSealed(CO_CREDIT_CUSTOMER, quoteId, CO_CART_LINES, CO_CART_TOTALS);

        MvcTestResult fresh = orders.quote(CO, CO_CREDIT_CUSTOMER, CO_CART.formatted("CREDIT"));
        assertThat(fresh).hasStatus(HttpStatus.CREATED);
        assertThat(json(fresh).required("lines")).isEqualTo(json(CO_CART_LINES_AFTER_THE_CHANGE));
        assertThat(json(fresh).required("totals"))
                .as("a fresh quote prices the changed catalog")
                .isEqualTo(json(CO_CART_TOTALS_AFTER_THE_CHANGE))
                .isNotEqualTo(order.required("totals"));
    }

    /**
     * The levers that priced a quote may be gone by the time it is confirmed: the sealed combo allocations, tier
     * discount and gift line are confirmed as they were quoted, because the order renders the sealed rows and never
     * looks up a rule. The quote is created through E-01 with the seeded levers, so the sealed rows are exactly what
     * the quote endpoint produced.
     */
    @Test
    void gt06ASealedQuoteKeepsItsComboTierAndGiftAfterTheLeversAreDeactivated() {
        MvcTestResult quoted = orders.quote(CO, CO_CASH_CUSTOMER, EVERY_COMPONENT_CART);
        assertThat(quoted).hasStatus(HttpStatus.CREATED);
        assertThat(json(quoted).required("lines")).as("quoted lines").isEqualTo(json(EVERY_COMPONENT_LINES));
        assertThat(json(quoted).required("totals")).as("quoted totals").isEqualTo(json(EVERY_COMPONENT_TOTALS));
        UUID quoteId = UUID.fromString(json(quoted).required("quoteId").stringValue());

        changeTheCatalog();
        MvcTestResult confirmed = orders.confirm(CO, CO_CASH_CUSTOMER, newKey(), quoteId);

        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        JsonNode order = json(confirmed);
        assertThat(order.required("lines")).as("confirmed lines").isEqualTo(json(EVERY_COMPONENT_LINES));
        assertThat(order.required("totals")).as("confirmed totals").isEqualTo(json(EVERY_COMPONENT_TOTALS));
        assertSealed(CO_CASH_CUSTOMER, quoteId, EVERY_COMPONENT_LINES, EVERY_COMPONENT_TOTALS);

        MvcTestResult fresh = orders.quote(CO, CO_CASH_CUSTOMER, EVERY_COMPONENT_CART);
        assertThat(fresh).hasStatus(HttpStatus.CREATED);
        assertThat(json(fresh).required("totals"))
                .as("a fresh quote prices the changed catalog with no lever")
                .isEqualTo(json(EVERY_COMPONENT_TOTALS_AFTER_THE_CHANGE))
                .isNotEqualTo(order.required("totals"));
    }

    /** The quote reads back as confirmed, with the breakdown it was sealed with (BR-07). */
    private void assertSealed(UUID customer, UUID quoteId, String lines, String totals) {
        MvcTestResult read = mvc.get().uri(QUOTES + "/{quoteId}", quoteId)
                .header(TENANT_HEADER, CO)
                .header(CUSTOMER_HEADER, customer.toString())
                .exchange();
        assertThat(read).hasStatus(HttpStatus.OK);
        JsonNode quote = json(read);
        assertThat(quote.required("status").stringValue()).isEqualTo("CONFIRMED");
        assertThat(quote.required("lines")).as("quote lines read back").isEqualTo(json(lines));
        assertThat(quote.required("totals")).as("quote totals read back").isEqualTo(json(totals));
    }

    private void changeTheCatalog() {
        setChipsPrice(1900);
        setLeversActive(false);
    }

    private void setChipsPrice(long listPrice) {
        jdbc.sql("UPDATE products SET list_price = :listPrice WHERE tenant_id = :tenantId AND sku = 'SNK-CHIPS-45'")
                .param("listPrice", listPrice)
                .param("tenantId", CO)
                .update();
    }

    private void setLeversActive(boolean active) {
        for (String table : LEVER_TABLES) {
            jdbc.sql("UPDATE " + table + " SET active = :active WHERE tenant_id = :tenantId")
                    .param("active", active)
                    .param("tenantId", CO)
                    .update();
        }
    }
}
