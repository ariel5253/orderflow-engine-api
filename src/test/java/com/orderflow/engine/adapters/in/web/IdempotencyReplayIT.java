package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.ordering.OrderTestSupport.ORDERS;
import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.ordering.OrderTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-24, AC-25 and SPEC section 7.3: the same key with the same request replays the stored response byte for byte
 * with Idempotent-Replayed: true, and confirms nothing again; the same key with another request is 422
 * IDEMPOTENCY_KEY_REUSED; a failed confirmation is not stored under its key, so the client can fix the cause and
 * retry with the same key. Requests are compared by the SHA-256 of method, path, customer and canonical JSON body.
 * Confirmations commit for real, so each test removes its orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyReplayIT {

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

    @Test
    void theSameKeyAndBodyReplayTheStoredResponseByteForByte() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        MvcTestResult first = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);
        assertThat(first).hasStatus(HttpStatus.CREATED);
        byte[] firstBody = first.getResponse().getContentAsByteArray();

        MvcTestResult replay = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);

        assertThat(first).headers().doesNotContainHeader(REPLAYED_HEADER);
        assertThat(replay).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(replay).headers().hasValue(REPLAYED_HEADER, "true");
        assertThat(replay).headers().hasValue(HttpHeaders.LOCATION,
                ORDERS + "/" + json(first).required("orderId").stringValue());
        assertThat(replay.getResponse().getContentAsByteArray()).isEqualTo(firstBody);
        assertThat(jdbc.sql("SELECT response_body FROM idempotency_keys WHERE idem_key = :key")
                .param("key", key)
                .query(byte[].class)
                .single())
                .as("the stored response is the body the first request received")
                .isEqualTo(firstBody);
        assertThat(orders.count("orders")).as("orders").isOne();
        assertThat(orders.count("credit_ledger")).as("credit movements").isOne();
        assertThat(orders.count("outbox_events")).as("outbox events").isEqualTo(2);
        assertThat(orders.count("idempotency_keys")).as("idempotency keys").isOne();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("credit consumed once").isEqualTo("22344.0000");
    }

    /**
     * The body is compared in canonical form: the order of its properties and its whitespace do not make it another
     * request. A property the contract does not define is ignored by the confirmation, but it is part of the body.
     */
    @Test
    void theSameBodyWrittenDifferentlyIsTheSameRequest() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        MvcTestResult first = orders.confirm(CO, CO_CREDIT_CUSTOMER, key,
                "{\"quoteId\":\"" + quoteId + "\",\"channel\":\"app\"}");

        MvcTestResult replay = orders.confirm(CO, CO_CREDIT_CUSTOMER, key,
                "\n  {  \"channel\" : \"app\",\n\t\"quoteId\" :\t\"" + quoteId + "\"  }\n");

        assertThat(replay).hasStatus(HttpStatus.CREATED);
        assertThat(replay).headers().hasValue(REPLAYED_HEADER, "true");
        assertThat(replay.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(orders.count("orders")).isOne();
    }

    @Test
    void theSameKeyWithAnotherBodyIsRejectedAndChangesNothing() {
        UUID firstQuote = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        UUID secondQuote = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        MvcTestResult first = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, firstQuote);
        assertThat(first).hasStatus(HttpStatus.CREATED);

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, secondQuote), HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_KEY_REUSED");

        assertThat(jdbc.sql("SELECT quote_id FROM orders").query(UUID.class).list())
                .as("only the first quote is confirmed")
                .containsExactly(firstQuote);
        assertThat(orders.count("idempotency_keys")).isOne();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("22344.0000");
        MvcTestResult replay = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, firstQuote);
        assertThat(replay.getResponse().getContentAsByteArray())
                .as("the key still replays its first response")
                .isEqualTo(first.getResponse().getContentAsByteArray());
    }

    /**
     * 477,657 consumed of 500,000 leaves 22,343, one less than the quote total. The failure is not stored under the
     * key, so once the credit is given back the same key and body confirm the order instead of replaying the 409.
     */
    @Test
    void aFailedConfirmationIsNotStoredAndTheSameKeyCanRetry() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 477_657);

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId), HttpStatus.CONFLICT,
                "CREDIT_LIMIT_EXCEEDED");
        orders.assertNothingConfirmed();

        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 0);
        MvcTestResult retry = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).headers().doesNotContainHeader(REPLAYED_HEADER);
        assertThat(orders.count("orders")).isOne();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("22344.0000");
    }

    /** SPEC section 7.1: request_hash = SHA-256(method + path + customerId + canonical JSON body), stored raw. */
    @Test
    void theStoredRequestHashIsTheSha256OfMethodPathCustomerAndCanonicalBody() throws NoSuchAlgorithmException {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, "{ \"quoteId\" : \"" + quoteId + "\" }"))
                .hasStatus(HttpStatus.CREATED);

        byte[] expected = MessageDigest.getInstance("SHA-256").digest(
                ("POST" + "/api/v1/orders" + CO_CREDIT_CUSTOMER + "{\"quoteId\":\"" + quoteId + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE idem_key = :key")
                .param("key", key)
                .query(byte[].class)
                .single())
                .isEqualTo(expected);
    }

    /** Keys belong to a customer: another customer's request under the same key is a request of its own. */
    @Test
    void anotherCustomerMayUseTheSameKey() {
        String key = newKey();
        MvcTestResult creditCustomer = orders.confirm(CO, CO_CREDIT_CUSTOMER, key,
                orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT"));
        MvcTestResult cashCustomer = orders.confirm(CO, CO_CASH_CUSTOMER, key,
                orders.quoteCoCart(CO_CASH_CUSTOMER, "CASH"));

        assertThat(creditCustomer).hasStatus(HttpStatus.CREATED);
        assertThat(cashCustomer).hasStatus(HttpStatus.CREATED);
        assertThat(cashCustomer).headers().doesNotContainHeader(REPLAYED_HEADER);
        assertThat(orders.count("orders")).isEqualTo(2);
        assertThat(orders.count("idempotency_keys")).isEqualTo(2);
    }
}
