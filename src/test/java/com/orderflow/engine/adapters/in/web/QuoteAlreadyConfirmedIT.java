package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.ordering.OrderTestSupport;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteTestData;
import java.time.Instant;
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
 * AC-28 and BD-03: a quote is confirmed once. A new key on a confirmed quote, as after an app reinstall, is 409
 * QUOTE_ALREADY_CONFIRMED with the number and id of the existing order, so the client can still find it. The rejection
 * confirms nothing again and is not stored under the new key. Confirmations commit for real, so each test removes its
 * orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QuoteAlreadyConfirmedIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private QuoteRepository quotes;

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
    void aNewKeyOnAConfirmedQuoteAnswersWithTheExistingOrder() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String firstKey = newKey();
        MvcTestResult first = orders.confirm(CO, CO_CREDIT_CUSTOMER, firstKey, quoteId);
        assertThat(first).hasStatus(HttpStatus.CREATED);
        JsonNode order = json(first);
        String secondKey = newKey();

        MvcTestResult second = orders.confirm(CO, CO_CREDIT_CUSTOMER, secondKey, quoteId);

        assertProblem(second, HttpStatus.CONFLICT, "QUOTE_ALREADY_CONFIRMED");
        JsonNode problem = json(second);
        assertThat(problem.required("orderNumber")).isEqualTo(order.required("orderNumber"));
        assertThat(problem.required("orderId")).isEqualTo(order.required("orderId"));
        assertThat(orders.count("orders")).as("orders").isOne();
        assertThat(orders.count("credit_ledger")).as("credit movements").isOne();
        assertThat(orders.count("outbox_events")).as("outbox events").isEqualTo(2);
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("credit consumed once").isEqualTo("22344.0000");
        assertThat(jdbc.sql("SELECT idem_key FROM idempotency_keys").query(String.class).list())
                .as("the rejection is not stored under the new key")
                .containsExactly(firstKey);
    }

    @Test
    void theFirstKeyStillReplaysAfterAnotherKeyWasRejected() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String firstKey = newKey();
        MvcTestResult first = orders.confirm(CO, CO_CREDIT_CUSTOMER, firstKey, quoteId);
        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.CONFLICT,
                "QUOTE_ALREADY_CONFIRMED");

        MvcTestResult replay = orders.confirm(CO, CO_CREDIT_CUSTOMER, firstKey, quoteId);

        assertThat(replay).hasStatus(HttpStatus.CREATED);
        assertThat(replay).headers().hasValue(REPLAYED_HEADER, "true");
        assertThat(replay.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
    }

    /**
     * A quote confirmed long ago has also passed its expiry, and its first key may have been purged after 24 hours
     * (SPEC section 12). A retry with a new key still learns the order number: the existing order wins over the
     * expiry.
     */
    @Test
    void aConfirmedQuoteThatHasExpiredStillAnswersWithItsOrder() {
        UUID quoteId = quotes.insert(QuoteTestData.threeColas(CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:15:00Z")));
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (:tenantId, :orderId, :quoteId, :customerId, 'CO-000777', '2026-01-01T00:05:00Z')""")
                .param("tenantId", CO)
                .param("orderId", orderId)
                .param("quoteId", quoteId)
                .param("customerId", CO_CREDIT_CUSTOMER)
                .update();

        MvcTestResult retry = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId);

        assertProblem(retry, HttpStatus.CONFLICT, "QUOTE_ALREADY_CONFIRMED");
        assertThat(json(retry).required("orderNumber").stringValue()).isEqualTo("CO-000777");
        assertThat(json(retry).required("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(orders.count("orders")).isOne();
        assertThat(orders.count("idempotency_keys")).isZero();
    }
}
