package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.GT;
import static com.orderflow.engine.quoting.QuoteTestData.GT_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.PE;
import static com.orderflow.engine.quoting.QuoteTestData.PE_CASH_CUSTOMER;
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
import tools.jackson.databind.JsonNode;

/**
 * Order numbers come from one sequence per country, order_number_seq_ followed by the country code, and carry the
 * country code as their prefix (data model section 4, api-contract section 1). Guatemala and Peru were added as data
 * only (SPEC section 5): their changelog creates their sequences, and no code names a country. So the first
 * confirmation in gt-alpha draws GT-000001 and the first one in pe-alpha draws PE-000001, while the sequences of the
 * other countries do not move. Each test confirms one order of its own country, and the class has a database of its
 * own, so these numbers are the first of their sequences. The class is not transactional: each confirmation commits
 * for real, as in production, so each test removes its orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CountryOrderNumberIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

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

    /**
     * Three 1.5 l colas at 15.00 with 12% tax, on credit: 45.00 plus 5.40 of tax, 50.40, which the 5,000.00 of credit
     * covers and the credit account then holds as consumed.
     */
    @Test
    void aGuatemalaConfirmationDrawsGt000001FromTheGuatemalaSequence() {
        UUID quoteId = quote(GT, GT_CREDIT_CUSTOMER, """
                {"paymentMethod": "CREDIT", "lines": [{"sku": "BEV-COLA-1500", "qty": 3}]}""");
        assertThat(orders.nextOrderNumber("GT")).as("next Guatemala number").isEqualTo("GT-000001");

        MvcTestResult confirmed = orders.confirm(GT, GT_CREDIT_CUSTOMER, newKey(), quoteId);

        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        JsonNode order = json(confirmed);
        assertThat(order.required("orderNumber").stringValue()).isEqualTo("GT-000001");
        assertThat(order.required("currency").stringValue()).isEqualTo("GTQ");
        assertThat(order.required("totals").required("total").stringValue()).isEqualTo("50.40");
        assertThat(orders.consumed(GT, GT_CREDIT_CUSTOMER)).isEqualTo("50.4000");
        assertThat(orders.nextOrderNumber("GT")).as("next Guatemala number").isEqualTo("GT-000002");
        assertNoNumberDrawn("CO", "EC");
    }

    /** Two 1.5 l colas at 7.50 with 18% tax, paid cash: 15.00 plus 2.70 of tax, 17.70. */
    @Test
    void aPeruConfirmationDrawsPe000001FromThePeruSequence() {
        UUID quoteId = quote(PE, PE_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [{"sku": "BEV-COLA-1500", "qty": 2}]}""");
        assertThat(orders.nextOrderNumber("PE")).as("next Peru number").isEqualTo("PE-000001");

        MvcTestResult confirmed = orders.confirm(PE, PE_CASH_CUSTOMER, newKey(), quoteId);

        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        JsonNode order = json(confirmed);
        assertThat(order.required("orderNumber").stringValue()).isEqualTo("PE-000001");
        assertThat(order.required("currency").stringValue()).isEqualTo("PEN");
        assertThat(order.required("totals").required("total").stringValue()).isEqualTo("17.70");
        assertThat(orders.nextOrderNumber("PE")).as("next Peru number").isEqualTo("PE-000002");
        assertNoNumberDrawn("CO", "EC");
    }

    private UUID quote(String tenant, UUID customer, String body) {
        MvcTestResult created = orders.quote(tenant, customer, body);
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(created).required("quoteId").stringValue());
    }

    /** No test of this class confirms an order in these countries, so their sequences are still at their start. */
    private void assertNoNumberDrawn(String... countries) {
        for (String country : countries) {
            assertThat(orders.nextOrderNumber(country)).as("next number of " + country).isEqualTo(country + "-000001");
        }
    }
}
