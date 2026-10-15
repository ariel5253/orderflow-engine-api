package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.ordering.OrderTestSupport.CO_CART_LINES;
import static com.orderflow.engine.ordering.OrderTestSupport.CO_CART_TOTALS;
import static com.orderflow.engine.ordering.OrderTestSupport.CUSTOMER_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.KEY_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.ORDERS;
import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.TENANT_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.confirmBody;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.ordering.OrderTestSupport;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteTestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.JsonNode;

/**
 * AC-23 and api-contract E-03: a confirmation needs an Idempotency-Key of 8 to 64 letters, digits, hyphens or
 * underscores; it answers 201 with the order rendered from its quote; and every error of the operation card is a
 * problem that leaves no order, credit movement, event or key behind. A request that meets a lock held by a
 * concurrent confirmation waits at most the lock timeout and then answers 409, and the locks are taken in the order
 * idempotency key, quote, credit account (SPEC section 7.1). Confirmations commit for real, so each test removes its
 * orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyContractIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** The order of the E-03 body fields in the contract. */
    private static final List<String> ORDER_FIELDS = List.of("orderId", "orderNumber", "status", "quoteId",
            "confirmedAt", "currency", "lines", "totals", "paymentMethod");

    /** Longer than the lock timeout of a confirmation, so a waiting confirmation always ends before it. */
    private static final Duration CONFIRMATION_LIMIT = Duration.ofSeconds(30);

    /** How long a probe of the lock tests waits for a lock before it reports the lock as held. */
    private static final String PROBE_LOCK_TIMEOUT = "200ms";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private QuoteRepository quotes;

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
    void aConfirmationWithoutAnIdempotencyKeyIsRejected() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");

        MvcTestResult result = mvc.post().uri(ORDERS)
                .header(TENANT_HEADER, CO)
                .header(CUSTOMER_HEADER, CO_CREDIT_CUSTOMER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(quoteId))
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING");
        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("0.0000");
    }

    /** Empty, 7 and 65 characters long, and characters outside letters, digits, hyphen and underscore. */
    @ParameterizedTest(name = "key [{0}]")
    @ValueSource(strings = {"", "abc-123", "abcdefgh-abcdefgh-abcdefgh-abcdefgh-abcdefgh-abcdefgh-abcdefgh-12",
            "has space 1234", "dotted.key.1234", "semi;colon-1234", "slash/key/1234", "\u00f1and\u00fa-key-1234"})
    void anIdempotencyKeyOutsideTheFormatIsInvalid(String key) {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId), HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_INVALID");
        orders.assertNothingConfirmed();
    }

    /** Keys of 8 and 64 characters, the shortest and the longest, with every kind of allowed character. */
    @ParameterizedTest(name = "key [{0}]")
    @ValueSource(strings = {"aZ0_-9zA", "abcdefgh_ABCDEFGH-01234567_abcdefgh-ABCDEFGH_01234567-abcdefgh_Z"})
    void keysOfEightToSixtyFourAllowedCharactersAreAccepted(String key) {
        UUID quoteId = orders.quoteCoCart(CO_CASH_CUSTOMER, "CASH");

        MvcTestResult result = orders.confirm(CO, CO_CASH_CUSTOMER, key, quoteId);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT idem_key FROM idempotency_keys").query(String.class).single()).isEqualTo(key);
    }

    @Test
    void aCreditConfirmationAnswers201WithTheOrderRenderedFromItsQuote() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String orderNumber = orders.nextOrderNumber("CO");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        MvcTestResult result = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId);
        Instant after = Instant.now();

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).headers().doesNotContainHeader(REPLAYED_HEADER);
        JsonNode body = json(result);
        String orderId = body.required("orderId").stringValue();
        String confirmedAt = body.required("confirmedAt").stringValue();
        assertThat(result).headers().hasValue(HttpHeaders.LOCATION, ORDERS + "/" + orderId);
        assertThat(UUID.fromString(orderId).version()).as("the order id is a UUIDv7").isEqualTo(7);
        assertThat(Instant.parse(confirmedAt)).isBetween(before, after);
        assertThat(body.propertyNames()).containsExactlyElementsOf(ORDER_FIELDS);
        assertThat(body).isEqualTo(json("""
                {"orderId": "%s", "orderNumber": "%s", "status": "CONFIRMED", "quoteId": "%s",
                 "confirmedAt": "%s", "currency": "COP",
                 "lines": %s,
                 "totals": %s,
                 "paymentMethod": "CREDIT"}"""
                .formatted(orderId, orderNumber, quoteId, confirmedAt, CO_CART_LINES, CO_CART_TOTALS)));
        assertThat(jdbc.sql("""
                        SELECT id, quote_id, customer_id, order_number, confirmed_at FROM orders
                        WHERE tenant_id = :tenantId""")
                .param("tenantId", CO)
                .query((rs, row) -> tuple(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant()))
                .single())
                .as("the order row holds its number and its quote, and no amount")
                .isEqualTo(tuple(UUID.fromString(orderId), quoteId, CO_CREDIT_CUSTOMER, orderNumber,
                        Instant.parse(confirmedAt)));
        assertThat(jdbc.sql("SELECT order_id, CAST(amount AS text) FROM credit_ledger")
                .query((rs, row) -> tuple(rs.getObject(1, UUID.class), rs.getString(2)))
                .single())
                .as("one credit movement of the quote total")
                .isEqualTo(tuple(UUID.fromString(orderId), "22344.0000"));
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("22344.0000");
    }

    @Test
    void aCashConfirmationConsumesNoCredit() {
        UUID quoteId = orders.quoteCoCart(CO_CASH_CUSTOMER, "CASH");

        MvcTestResult result = orders.confirm(CO, CO_CASH_CUSTOMER, newKey(), quoteId);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(json(result).required("paymentMethod").stringValue()).isEqualTo("CASH");
        assertThat(json(result).required("totals")).isEqualTo(json(CO_CART_TOTALS));
        assertThat(orders.count("orders")).isOne();
        assertThat(orders.count("credit_ledger")).isZero();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("0.0000");
    }

    /** 500,000 of limit minus 477,656 consumed leaves exactly the 22,344 of the quote: a total equal to it fits. */
    @Test
    void aCreditTotalEqualToTheAvailableCreditFits() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 477_656);

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId)).hasStatus(HttpStatus.CREATED);

        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("500000.0000");
    }

    /** 500,000 of limit minus 477,657 consumed leaves 22,343, one less than the quote total. */
    @Test
    void aCreditTotalAboveTheAvailableCreditIsRejectedAndLeavesNothing() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 477_657);

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.CONFLICT,
                "CREDIT_LIMIT_EXCEEDED");

        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("477657.0000");
    }

    @Test
    void aQuoteOfAnotherCustomerOrTenantIsNotFound() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");

        assertProblem(orders.confirm(CO, CO_CASH_CUSTOMER, newKey(), quoteId), HttpStatus.NOT_FOUND,
                "QUOTE_NOT_FOUND");
        assertProblem(orders.confirm(EC, EC_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.NOT_FOUND,
                "QUOTE_NOT_FOUND");
        assertProblem(orders.confirm(EC, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.NOT_FOUND,
                "QUOTE_NOT_FOUND");
        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), UUID.randomUUID()), HttpStatus.NOT_FOUND,
                "QUOTE_NOT_FOUND");
        assertProblem(orders.confirm(CO, UUID.randomUUID(), newKey(), quoteId), HttpStatus.NOT_FOUND,
                "QUOTE_NOT_FOUND");
        orders.assertNothingConfirmed();
    }

    @Test
    void anExpiredQuoteIsRejectedAndLeavesNothing() {
        UUID quoteId = quotes.insert(QuoteTestData.threeColas(CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:15:00Z")));

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.CONFLICT,
                "QUOTE_EXPIRED");

        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("0.0000");
    }

    @Test
    void anUnknownTenantIsRejected() {
        assertProblem(orders.confirm("zz-unknown", CO_CREDIT_CUSTOMER, newKey(), UUID.randomUUID()),
                HttpStatus.BAD_REQUEST, "TENANT_NOT_FOUND");
        orders.assertNothingConfirmed();
    }

    @ParameterizedTest(name = "body {0}")
    @ValueSource(strings = {"{}", "{\"quoteId\": null}", "{\"quoteId\": \"not-a-uuid\"}", "{\"quoteId\": 42}",
            "{\"quoteId\": \"1-1-1-1-1\"}", "[]", "\"text\"", "{", ""})
    void aBodyWithoutAQuoteIdIsMalformed(String body) {
        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), body), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        orders.assertNothingConfirmed();
    }

    @Test
    void aMissingOrMalformedCustomerHeaderIsMalformed() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");

        assertProblem(mvc.post().uri(ORDERS)
                        .header(TENANT_HEADER, CO)
                        .header(KEY_HEADER, newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(quoteId))
                        .exchange(),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertProblem(mvc.post().uri(ORDERS)
                        .header(TENANT_HEADER, CO)
                        .header(CUSTOMER_HEADER, "customer-1")
                        .header(KEY_HEADER, newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(quoteId))
                        .exchange(),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        orders.assertNothingConfirmed();
    }

    /*
     * The lock tests hold one of the confirmation's locks on a connection of their own, start a confirmation that
     * must wait for it, check which other locks the waiting confirmation already holds, and let it time out.
     */

    @Test
    void aRequestWhoseKeyIsStillInFlightWaitsAndThenAnswers409() throws Exception {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();

        MvcTestResult result;
        try (Connection inFlight = transaction()) {
            execute(inFlight, """
                    INSERT INTO idempotency_keys (tenant_id, customer_id, idem_key, request_hash, created_at)
                    VALUES (?, ?, ?, ?, now())""", CO, CO_CREDIT_CUSTOMER, key, new byte[32]);
            CompletableFuture<MvcTestResult> waiting = confirmInBackground(key, quoteId);
            awaitBlockedOnALock(waiting);
            result = waiting.get(CONFIRMATION_LIMIT.toSeconds(), TimeUnit.SECONDS);
            inFlight.rollback();
        }

        assertProblem(result, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS");
        orders.assertNothingConfirmed();
        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId))
                .as("the key is free once the request in flight ends")
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void whileItWaitsForTheQuoteAConfirmationHoldsItsKeyButNotTheCredit() throws Exception {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();

        MvcTestResult result;
        try (Connection quoteHolder = transaction()) {
            execute(quoteHolder, "SELECT id FROM quotes WHERE tenant_id = ? AND id = ? FOR UPDATE", CO, quoteId);
            CompletableFuture<MvcTestResult> waiting = confirmInBackground(key, quoteId);
            awaitBlockedOnALock(waiting);

            assertThat(keyIsHeld(key)).as("idempotency key held while the quote is awaited").isTrue();
            assertThat(creditIsHeld()).as("credit account held while the quote is awaited").isFalse();
            result = waiting.get(CONFIRMATION_LIMIT.toSeconds(), TimeUnit.SECONDS);
            quoteHolder.rollback();
        }

        assertProblem(result, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS");
        orders.assertNothingConfirmed();
    }

    @Test
    void whileItWaitsForTheCreditAConfirmationHoldsItsKeyAndItsQuote() throws Exception {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();

        MvcTestResult result;
        try (Connection creditHolder = transaction()) {
            execute(creditHolder, """
                    SELECT consumed FROM credit_accounts WHERE tenant_id = ? AND customer_id = ? FOR UPDATE""",
                    CO, CO_CREDIT_CUSTOMER);
            CompletableFuture<MvcTestResult> waiting = confirmInBackground(key, quoteId);
            awaitBlockedOnALock(waiting);

            assertThat(keyIsHeld(key)).as("idempotency key held while the credit is awaited").isTrue();
            assertThat(quoteIsHeld(quoteId)).as("quote held while the credit is awaited").isTrue();
            result = waiting.get(CONFIRMATION_LIMIT.toSeconds(), TimeUnit.SECONDS);
            creditHolder.rollback();
        }

        assertProblem(result, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS");
        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).isEqualTo("0.0000");
    }

    private CompletableFuture<MvcTestResult> confirmInBackground(String key, UUID quoteId) {
        return CompletableFuture.supplyAsync(() -> orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId));
    }

    /** Waits until a session of the database waits for a lock, which only the confirmation can do here. */
    private void awaitBlockedOnALock(CompletableFuture<MvcTestResult> confirmation) throws InterruptedException {
        long deadline = System.nanoTime() + CONFIRMATION_LIMIT.toNanos();
        while (jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'""")
                .query(Long.class)
                .single() == 0) {
            if (confirmation.isDone() || System.nanoTime() > deadline) {
                fail("the confirmation never waited for a lock");
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private boolean keyIsHeld(String key) throws SQLException {
        return isLocked("""
                INSERT INTO idempotency_keys (tenant_id, customer_id, idem_key, request_hash, created_at)
                VALUES (?, ?, ?, ?, now()) ON CONFLICT DO NOTHING""", CO, CO_CREDIT_CUSTOMER, key, new byte[32]);
    }

    private boolean quoteIsHeld(UUID quoteId) throws SQLException {
        return isLocked("SELECT id FROM quotes WHERE tenant_id = ? AND id = ? FOR UPDATE", CO, quoteId);
    }

    private boolean creditIsHeld() throws SQLException {
        return isLocked("SELECT consumed FROM credit_accounts WHERE tenant_id = ? AND customer_id = ? FOR UPDATE",
                CO, CO_CREDIT_CUSTOMER);
    }

    /**
     * Runs the statement in a transaction that gives up waiting for a lock after a short while, and rolls it back.
     * A statement that gives up (SQLSTATE 55P03, lock_not_available) met a lock held by another transaction.
     */
    private boolean isLocked(String sql, Object... args) throws SQLException {
        try (Connection probe = transaction()) {
            try (Statement setup = probe.createStatement()) {
                setup.execute("SET LOCAL lock_timeout = '" + PROBE_LOCK_TIMEOUT + "'");
            }
            try {
                execute(probe, sql, args);
                return false;
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) {
                    return true;
                }
                throw e;
            } finally {
                probe.rollback();
            }
        }
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }
}
