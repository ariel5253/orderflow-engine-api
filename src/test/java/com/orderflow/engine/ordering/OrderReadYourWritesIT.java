package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * AC-31, I-201 and SPEC section 7.6 ("history read from a replica or the ERP"): the order a 201 names is readable at
 * once, by its id with the body of the 201 and first in its customer's history with the total of the 201, also while
 * other confirmations of the same customer are in flight. Both reads go to the primary tables the confirmation
 * committed to, with no copy, cache or external system in between. The class is not transactional: each confirmation
 * and each read runs in a transaction of its own and commits for real, as in production, so each test removes its
 * orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderReadYourWritesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** The total of {@link OrderTestSupport#CO_CART}: 19,200 of net plus 2,964 + 180 of tax. */
    private static final String CO_CART_TOTAL = "22344";

    /** How many confirmations of the same customer run at once. */
    private static final int CONCURRENT_CONFIRMATIONS = 8;

    /** Longer than any confirmation and its reads take, so a request that hangs fails the test instead. */
    private static final Duration LIMIT = Duration.ofSeconds(60);

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
    void eachOrderIsReadableByIdAndFirstInTheHistoryRightAfterIts201() {
        List<String> newestFirst = new ArrayList<>();
        for (int confirmation = 1; confirmation <= 3; confirmation++) {
            UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
            MvcTestResult created = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId);
            assertThat(created).hasStatus(HttpStatus.CREATED);
            JsonNode order = json(created);
            String orderId = order.required("orderId").stringValue();

            MvcTestResult byId = orders.readOrder(CO, CO_CREDIT_CUSTOMER, orderId);
            MvcTestResult history = orders.history(CO, CO_CREDIT_CUSTOMER);

            newestFirst.addFirst(order.required("orderNumber").stringValue());
            assertThat(order.required("totals").required("total").stringValue()).isEqualTo(CO_CART_TOTAL);
            assertThat(byId).as("order %s read by its id", orderId).hasStatus(HttpStatus.OK);
            assertThat(json(byId)).as("order %s read by its id", orderId).isEqualTo(order);
            assertThat(history).as("history after order %s", orderId).hasStatus(HttpStatus.OK);
            JsonNode items = json(history).required("items");
            assertThat(items.get(0)).as("first item of the history after order %s", orderId).isEqualTo(json("""
                    {"orderId": "%s", "orderNumber": "%s", "confirmedAt": "%s", "total": "%s"}""".formatted(
                    orderId, order.required("orderNumber").stringValue(), order.required("confirmedAt").stringValue(),
                    CO_CART_TOTAL)));
            assertThat(numbers(items)).as("history after order %s", orderId).isEqualTo(newestFirst);
        }
    }

    @Test
    void ordersConfirmedConcurrentlyAreEachReadableRightAfterTheir201() throws Exception {
        List<UUID> quoteIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CONFIRMATIONS; i++) {
            quoteIds.add(orders.quoteCoCart(CO_CASH_CUSTOMER, "CASH"));
        }

        List<Reads> reads = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CONFIRMATIONS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Reads>> confirmations = quoteIds.stream()
                    .map(quoteId -> CompletableFuture.supplyAsync(() -> confirmAndRead(start, quoteId), pool))
                    .toList();
            start.countDown();
            for (CompletableFuture<Reads> confirmation : confirmations) {
                reads.add(confirmation.get(LIMIT.toSeconds(), TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        for (Reads read : reads) {
            String orderId = read.created().required("orderId").stringValue();
            String orderNumber = read.created().required("orderNumber").stringValue();
            assertThat(read.byId()).as("order %s read by its id", orderNumber).isEqualTo(read.created());
            assertThat(numbers(read.history())).as("history read right after order %s", orderNumber)
                    .contains(orderNumber);
            assertThat(read.history().valueStream()
                    .filter(item -> item.required("orderId").stringValue().equals(orderId))
                    .map(item -> item.required("total").stringValue()))
                    .as("total of order %s in the history", orderNumber)
                    .containsExactly(CO_CART_TOTAL);
        }
        JsonNode finalHistory = json(orders.history(CO, CO_CASH_CUSTOMER)).required("items");
        assertThat(numbers(finalHistory)).as("orders in the history")
                .containsExactlyInAnyOrderElementsOf(reads.stream()
                        .map(read -> read.created().required("orderNumber").stringValue())
                        .toList());
        List<Instant> confirmedAt = finalHistory.valueStream()
                .map(item -> Instant.parse(item.required("confirmedAt").stringValue()))
                .toList();
        assertThat(confirmedAt).as("instants of the history, newest first")
                .isSortedAccordingTo((first, second) -> second.compareTo(first));
    }

    /**
     * Waits for the start signal, confirms the quote, and right after its 201 reads the order by its id and reads the
     * history of its customer.
     */
    private Reads confirmAndRead(CountDownLatch start, UUID quoteId) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted before confirming quote " + quoteId, e);
        }
        MvcTestResult created = orders.confirm(CO, CO_CASH_CUSTOMER, newKey(), quoteId);
        assertThat(created).as("confirmation of quote %s", quoteId).hasStatus(HttpStatus.CREATED);
        JsonNode order = json(created);
        MvcTestResult byId = orders.readOrder(CO, CO_CASH_CUSTOMER, order.required("orderId").stringValue());
        MvcTestResult history = orders.history(CO, CO_CASH_CUSTOMER.toString(), CO_CASH_CUSTOMER.toString(), "100");
        assertThat(byId).as("read by id after quote %s", quoteId).hasStatus(HttpStatus.OK);
        assertThat(history).as("history after quote %s", quoteId).hasStatus(HttpStatus.OK);
        return new Reads(order, json(byId), json(history).required("items"));
    }

    private static List<String> numbers(JsonNode items) {
        return items.valueStream().map(item -> item.required("orderNumber").stringValue()).toList();
    }

    /** The 201 body of a confirmation, and the order and the history items read right after it. */
    private record Reads(JsonNode created, JsonNode byId, JsonNode history) {
    }
}
