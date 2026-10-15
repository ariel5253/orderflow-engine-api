package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-26, AC-27 and SPEC section 7.1: concurrent confirmations never duplicate an order and never consume credit twice
 * or past the limit.
 *
 * <ul>
 *   <li>Requests sent at once with one key confirm one order with one credit movement: one request creates it and
 *       every other one waits for it, then replays its answer byte for byte.</li>
 *   <li>Two confirmations that each fit in the available credit, but not together, run at once: exactly one succeeds
 *       and the other is 409 CREDIT_LIMIT_EXCEEDED, which consumes nothing and stores no key.</li>
 * </ul>
 *
 * <p>The overlap is made certain, not left to timing: the test holds the customer's credit account row on a
 * connection of its own, sends the requests, waits until every one of them is blocked on a lock of the confirmation,
 * and only then releases the row. The waits stay within the confirmation's lock timeout. Confirmations commit for
 * real, so each test removes its orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ConfirmOrderConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** Each request holds a pooled connection while it waits; the pool has ten, and the test needs two of its own. */
    private static final int SAME_KEY_REQUESTS = 6;

    /**
     * 45 colas of 1.5 l at 5,200 with 19% tax: 234,000 plus 44,460 of tax, 278,460. One order fits in the 500,000
     * limit; two, 556,920, do not.
     */
    private static final String MORE_THAN_HALF_THE_LIMIT = """
            {"paymentMethod": "CREDIT", "lines": [{"sku": "BEV-COLA-1500", "qty": 45}]}""";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

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
    void concurrentRequestsWithOneKeyConfirmOneOrderAndConsumeCreditOnce() throws Exception {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        List<Supplier<MvcTestResult>> requests = new ArrayList<>();
        for (int i = 0; i < SAME_KEY_REQUESTS; i++) {
            requests.add(() -> orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId));
        }

        List<MvcTestResult> answers = sendWhileTheCreditAccountIsHeld(requests);

        assertThat(answers).extracting(answer -> answer.getResponse().getStatus())
                .as("statuses")
                .containsOnly(HttpStatus.CREATED.value());
        assertThat(answers).filteredOn(answer -> !answer.getResponse().containsHeader(REPLAYED_HEADER))
                .as("answers that created the order")
                .hasSize(1);
        byte[] created = answers.getFirst().getResponse().getContentAsByteArray();
        String location = answers.getFirst().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(answers).allSatisfy(answer -> {
            assertThat(answer.getResponse().getContentAsByteArray()).isEqualTo(created);
            assertThat(answer).headers().hasValue(HttpHeaders.LOCATION, location);
        });
        assertThat(orders.count("orders")).as("orders").isOne();
        assertThat(orders.count("outbox_events")).as("outbox events").isEqualTo(2);
        assertThat(orders.count("idempotency_keys")).as("idempotency keys").isOne();
        assertThat(orders.ledgerAmounts()).as("credit movements").containsExactly("22344.0000");
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("credit consumed once").isEqualTo("22344.0000");
    }

    @Test
    void twoConfirmationsThatTogetherExceedTheLimitConfirmExactlyOne() throws Exception {
        List<UUID> quotes = List.of(quoteMoreThanHalfTheLimit(), quoteMoreThanHalfTheLimit());
        List<String> keys = List.of(newKey(), newKey());

        List<MvcTestResult> answers = sendWhileTheCreditAccountIsHeld(List.of(
                () -> orders.confirm(CO, CO_CREDIT_CUSTOMER, keys.get(0), quotes.get(0)),
                () -> orders.confirm(CO, CO_CREDIT_CUSTOMER, keys.get(1), quotes.get(1))));

        int winner = answers.get(0).getResponse().getStatus() == HttpStatus.CREATED.value() ? 0 : 1;
        assertThat(answers.get(winner)).hasStatus(HttpStatus.CREATED);
        assertProblem(answers.get(1 - winner), HttpStatus.CONFLICT, "CREDIT_LIMIT_EXCEEDED");
        assertThat(jdbc.sql("SELECT quote_id FROM orders").query(UUID.class).list())
                .as("confirmed quotes")
                .containsExactly(quotes.get(winner));
        assertThat(jdbc.sql("SELECT idem_key FROM idempotency_keys").query(String.class).list())
                .as("stored keys")
                .containsExactly(keys.get(winner));
        assertThat(orders.count("outbox_events")).as("outbox events").isEqualTo(2);
        assertThat(orders.ledgerAmounts()).as("credit movements").containsExactly("278460.0000");
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("278460.0000");
    }

    private UUID quoteMoreThanHalfTheLimit() {
        MvcTestResult quoted = orders.quote(CO, CO_CREDIT_CUSTOMER, MORE_THAN_HALF_THE_LIMIT);
        assertThat(quoted).hasStatus(HttpStatus.CREATED);
        assertThat(json(quoted).required("totals").required("total").stringValue()).isEqualTo("278460");
        return UUID.fromString(json(quoted).required("quoteId").stringValue());
    }

    /**
     * Sends every request on a thread of its own, all released at once, while the customer's credit account row is
     * locked by this test, and releases the row once all of them wait for a lock: the requests then overlap for
     * certain. Every answer is awaited before anything is asserted, so no request outlives the test. The answers come
     * back in the order of the requests.
     */
    private List<MvcTestResult> sendWhileTheCreditAccountIsHeld(List<Supplier<MvcTestResult>> requests)
            throws Exception {
        int count = requests.size();
        ExecutorService clients = Executors.newFixedThreadPool(count);
        CountDownLatch released = new CountDownLatch(1);
        CyclicBarrier together = new CyclicBarrier(count, released::countDown);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockCreditAccount(holder);
            List<Future<MvcTestResult>> sent = requests.stream()
                    .map(request -> clients.submit(() -> {
                        together.await();
                        return request.get();
                    }))
                    .toList();
            long waiting;
            try {
                assertThat(released.await(30, TimeUnit.SECONDS)).as("requests released together").isTrue();
                waiting = awaitLockWaits(count);
            } finally {
                holder.rollback();
            }
            List<MvcTestResult> answers = new ArrayList<>();
            for (Future<MvcTestResult> answer : sent) {
                answers.add(answer.get(30, TimeUnit.SECONDS));
            }
            assertThat(waiting).as("requests waiting for a lock at once").isEqualTo(count);
            return answers;
        } finally {
            clients.shutdownNow();
        }
    }

    private static void lockCreditAccount(Connection holder) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement("""
                SELECT consumed FROM credit_accounts WHERE tenant_id = ? AND customer_id = ? FOR UPDATE""")) {
            lock.setString(1, CO);
            lock.setObject(2, CO_CREDIT_CUSTOMER);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).as("credit account of the customer").isTrue();
            }
        }
    }

    /**
     * Waits until that many sessions wait for a lock at once, for at most half the confirmation's lock timeout, so the
     * first of them never gives up before the row is released, and returns how many were seen waiting.
     */
    private long awaitLockWaits(int sessions) {
        Instant deadline = Instant.now().plus(ConfirmOrderUseCase.LOCK_TIMEOUT.dividedBy(2));
        long waiting = lockWaits();
        while (waiting < sessions && Instant.now().isBefore(deadline)) {
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            waiting = lockWaits();
        }
        return waiting;
    }

    private long lockWaits() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'""")
                .query(Long.class)
                .single();
    }
}
