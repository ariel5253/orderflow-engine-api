package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.CO_CART;
import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * AC-17 (confirm side) and BR-06: the credit limit reflects consumption. Confirming the GT-05 quote, whose total
 * exceeds the available credit, is 409 CREDIT_LIMIT_EXCEEDED and leaves no order and no ledger row. A credit order
 * lowers the available credit by exactly its quoted total, exactly once, and consumed always equals the sum of the
 * ledger. A total equal to the available credit fits; one unit more does not. A cash order consumes nothing. The
 * co-alpha credit customer has a 500,000 limit. Confirmations commit for real, so each test removes its orders and
 * quotes and gives the credit back afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CreditLimitIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /**
     * The GT-05 cart (SPEC section 11): 60 colas of 1.5 l at 5,200 and 40 waters at 1,500, both at 19% tax, so
     * 372,000 plus 70,680 of tax, 442,680. With 100,000 of the 500,000 consumed, 400,000 is available and the quote
     * falls 42,680 short.
     */
    private static final String GT05_CART = """
            {"paymentMethod": "CREDIT",
             "lines": [{"sku": "BEV-WATER-600", "qty": 40}, {"sku": "BEV-COLA-1500", "qty": 60}]}""";

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
    void removeOrdersAndQuotes() {
        orders.deleteOrdersAndQuotes();
    }

    @Test
    void gt05AQuoteAboveTheAvailableCreditIsNotConfirmed() {
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 100_000);
        MvcTestResult quoted = orders.quote(CO, CO_CREDIT_CUSTOMER, GT05_CART);
        assertThat(quoted).hasStatus(HttpStatus.CREATED);
        assertThat(json(quoted).required("totals").required("total").stringValue()).isEqualTo("442680");
        assertThat(json(quoted).required("credit")).isEqualTo(json("""
                {"fits": false, "available": "400000", "shortfall": "42680"}"""));
        UUID quoteId = UUID.fromString(json(quoted).required("quoteId").stringValue());

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.CONFLICT,
                "CREDIT_LIMIT_EXCEEDED");

        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("100000.0000");
    }

    /**
     * {@link OrderTestSupport#CO_CART} totals 22,344. One order leaves 500,000 - 22,344 = 477,656 available, and a
     * replay of it consumes nothing again; a second order brings consumed to 44,688.
     */
    @Test
    void aCreditOrderLowersTheAvailableCreditByItsTotalExactlyOnce() {
        String key = newKey();
        UUID firstQuote = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        MvcTestResult first = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, firstQuote);
        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(json(first).required("totals").required("total").stringValue()).isEqualTo("22344");

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, firstQuote)).headers()
                .hasValue(REPLAYED_HEADER, "true");

        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed after one order").isEqualTo("22344.0000");
        assertThat(ledgerAmountOf(orderId(first))).as("credit movement of the order").isEqualTo("22344.0000");
        MvcTestResult second = orders.quote(CO, CO_CREDIT_CUSTOMER, CO_CART.formatted("CREDIT"));
        assertThat(json(second).required("credit")).as("credit check of the next quote").isEqualTo(json("""
                {"fits": true, "available": "477656", "shortfall": "0"}"""));

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(),
                UUID.fromString(json(second).required("quoteId").stringValue()))).hasStatus(HttpStatus.CREATED);

        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed after two orders").isEqualTo("44688.0000");
        assertThat(orders.ledgerAmounts()).containsExactly("22344.0000", "22344.0000");
        assertThat(consumedIsTheSumOfTheLedger()).as("consumed = sum of the ledger").isTrue();
    }

    /** 477,656 consumed leaves exactly the 22,344 of the order: a total equal to the available credit fits. */
    @Test
    void aTotalEqualToTheAvailableCreditFits() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 477_656);

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId)).hasStatus(HttpStatus.CREATED);

        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed reaches the limit").isEqualTo("500000.0000");
        assertThat(orders.ledgerAmounts()).containsExactly("22344.0000");
    }

    /** 477,657 consumed leaves 22,343, one less than the order total. */
    @Test
    void aTotalOneAboveTheAvailableCreditIsRejected() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 477_657);

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.CONFLICT,
                "CREDIT_LIMIT_EXCEEDED");

        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("477657.0000");
    }

    /** A customer with a credit account may still pay cash, and then the account is not touched. */
    @Test
    void aCashOrderConsumesNoCredit() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CASH");

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId)).hasStatus(HttpStatus.CREATED);

        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("0.0000");
        assertThat(orders.ledgerAmounts()).as("credit movements").isEmpty();
    }

    private String ledgerAmountOf(UUID orderId) {
        return jdbc.sql("SELECT CAST(amount AS text) FROM credit_ledger WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private boolean consumedIsTheSumOfTheLedger() {
        return jdbc.sql("""
                        SELECT a.consumed = (
                            SELECT sum(l.amount)
                            FROM credit_ledger l
                            JOIN orders o ON o.tenant_id = l.tenant_id AND o.id = l.order_id
                            WHERE o.tenant_id = a.tenant_id AND o.customer_id = a.customer_id)
                        FROM credit_accounts a
                        WHERE a.tenant_id = :tenantId AND a.customer_id = :customerId""")
                .param("tenantId", CO)
                .param("customerId", CO_CREDIT_CUSTOMER)
                .query(Boolean.class)
                .single();
    }

    private static UUID orderId(MvcTestResult confirmed) {
        return UUID.fromString(json(confirmed).required("orderId").stringValue());
    }
}
