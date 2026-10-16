package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
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
 * AC-32 and P-04: a confirmed order records exactly one PUSH and one ERP event in its own transaction, each due at
 * once, unleased and never attempted, with an id of its own that consumers deduplicate on. A replay records no event
 * again, and a rejected confirmation records none. Confirmations commit for real, so each test removes its orders and
 * quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OutboxWriteIT {

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
    void aConfirmedOrderRecordsOnePendingEventForPushAndOneForTheErp() {
        MvcTestResult confirmed = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(),
                orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT"));
        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        UUID orderId = UUID.fromString(json(confirmed).required("orderId").stringValue());
        Instant confirmedAt = Instant.parse(json(confirmed).required("confirmedAt").stringValue());

        assertThat(events())
                .containsExactlyInAnyOrder(
                        tuple(CO, orderId, "ERP", "PENDING", 0, confirmedAt, null, null, confirmedAt),
                        tuple(CO, orderId, "PUSH", "PENDING", 0, confirmedAt, null, null, confirmedAt));
        List<UUID> eventIds = jdbc.sql("SELECT id FROM outbox_events").query(UUID.class).list();
        assertThat(eventIds).doesNotHaveDuplicates();
        assertThat(eventIds).allSatisfy(id -> assertThat(id.version()).as("event id version").isEqualTo(7));
    }

    @Test
    void everyOrderHasItsOwnEvents() {
        MvcTestResult credit = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(),
                orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT"));
        MvcTestResult cash = orders.confirm(CO, CO_CASH_CUSTOMER, newKey(),
                orders.quoteCoCart(CO_CASH_CUSTOMER, "CASH"));

        assertThat(jdbc.sql("SELECT order_id, destination FROM outbox_events")
                .query((rs, row) -> tuple(rs.getObject(1, UUID.class), rs.getString(2)))
                .list())
                .containsExactlyInAnyOrder(
                        tuple(orderId(credit), "PUSH"), tuple(orderId(credit), "ERP"),
                        tuple(orderId(cash), "PUSH"), tuple(orderId(cash), "ERP"));
    }

    @Test
    void aReplayRecordsNoEventAgain() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId)).hasStatus(HttpStatus.CREATED);
        List<UUID> recorded = jdbc.sql("SELECT id FROM outbox_events").query(UUID.class).list();

        assertThat(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId)).hasStatus(HttpStatus.CREATED);

        assertThat(jdbc.sql("SELECT id FROM outbox_events").query(UUID.class).list())
                .containsExactlyInAnyOrderElementsOf(recorded);
    }

    /** 477,657 consumed of 500,000 leaves 22,343, one less than the quote total: the order is never confirmed. */
    @Test
    void aRejectedConfirmationRecordsNoEvent() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        orders.setConsumed(CO, CO_CREDIT_CUSTOMER, 477_657);

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId), HttpStatus.CONFLICT,
                "CREDIT_LIMIT_EXCEEDED");

        assertThat(orders.count("outbox_events")).isZero();
    }

    private List<Tuple> events() {
        return jdbc.sql("""
                        SELECT tenant_id, order_id, destination, status, attempts, next_attempt_at, locked_until,
                               last_error, created_at
                        FROM outbox_events""")
                .query((rs, row) -> tuple(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getObject(6, OffsetDateTime.class).toInstant(),
                        rs.getObject(7, OffsetDateTime.class), rs.getString(8),
                        rs.getObject(9, OffsetDateTime.class).toInstant()))
                .list();
    }

    private static UUID orderId(MvcTestResult confirmed) {
        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(confirmed).required("orderId").stringValue());
    }
}
