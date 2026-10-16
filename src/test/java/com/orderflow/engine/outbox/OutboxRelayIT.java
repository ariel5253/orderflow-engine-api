package com.orderflow.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.orderflow.engine.adapters.out.erp.FakeErpGateway;
import com.orderflow.engine.adapters.out.push.FakePushGateway;
import com.orderflow.engine.config.OutboxProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The relay delivers every confirmed order to push and the ERP at least once, and the gateways apply it once (SPEC
 * section 7.4). The test moves a clock by hand and draws every backoff at its ceiling, so leases and retry times are
 * reached exactly. The test configuration sets the poll interval to one hour, so the background poll never runs
 * during a test, and the test drives each poll itself. Nothing here runs in a test transaction: the relay must commit
 * on its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class OutboxRelayIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final Instant START = Instant.parse("2026-10-15T12:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(2);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(5);
    private static final Duration JUST_BEFORE = Duration.ofMillis(1);
    /** A claim takes milliseconds. It runs this long only if it waits for a row another relay holds. */
    private static final Duration CLAIM_LIMIT = Duration.ofSeconds(10);

    private static final String TENANT = "co-alpha";
    /** Tienda Sur, a seeded customer of co-alpha. */
    private static final UUID CUSTOMER = UUID.fromString("01a13cdb-cc00-7001-8001-000000000002");

    @TestBean
    private Clock clock;

    @TestBean
    private BackoffPolicy outboxBackoff;

    @MockitoSpyBean
    private FakePushGateway push;

    @MockitoSpyBean
    private FakeErpGateway erp;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private FixedDelayTask outboxRelayPoll;

    @Autowired
    private JdbcClient jdbc;

    static Clock clock() {
        return CLOCK;
    }

    /** Every wait is drawn at its ceiling, so each retry time is known exactly. */
    static BackoffPolicy outboxBackoff() {
        return new BackoffPolicy(BACKOFF_BASE, BACKOFF_MAX, Draws.highest());
    }

    @BeforeEach
    void startFromAnEmptyOutbox() {
        CLOCK.set(START);
        jdbc.sql("DELETE FROM outbox_events").update();
        jdbc.sql("DELETE FROM orders").update();
        jdbc.sql("DELETE FROM quotes").update();
    }

    @AfterEach
    void bringTheGatewaysBack() {
        push.recover();
        erp.recover();
    }

    @Test
    void deliversEachEventToItsDestinationAndMarksItSent() {
        Order order = insertOrder();
        UUID pushEvent = insertDueEvent(order, Destination.PUSH);
        UUID erpEvent = insertDueEvent(order, Destination.ERP);

        assertThat(relay.relayDue()).as("events claimed").isEqualTo(2);

        assertThat(row(pushEvent)).isEqualTo(EventRow.sent(0, null));
        assertThat(row(erpEvent)).isEqualTo(EventRow.sent(0, null));
        assertThat(push.effects(pushEvent)).as("push notifications shown").isEqualTo(1);
        assertThat(erp.order(order.number())).as("ERP order")
                .contains(new OrderConfirmedEvent(erpEvent, TENANT, order.id(), order.number(), CUSTOMER,
                        order.quoteId(), START));
        assertThat(relay.relayDue()).as("events claimed once sent").isZero();
        assertThat(push.calls(pushEvent) + erp.calls(erpEvent)).as("gateway calls").isEqualTo(2);
        assertThat(gauge("outbox_pending")).isZero();
    }

    @Test
    void callsTheGatewaysOutsideAnyDatabaseTransaction() {
        Order order = insertOrder();
        insertDueEvent(order, Destination.PUSH);
        insertDueEvent(order, Destination.ERP);
        List<String> duringTheCalls = new CopyOnWriteArrayList<>();
        Answer<Void> probe = invocation -> {
            duringTheCalls.add(transactionsOpenNow());
            invocation.callRealMethod();
            return null;
        };
        doAnswer(probe).when(push).send(any());
        doAnswer(probe).when(erp).publish(any());

        relay.relayDue();

        assertThat(duringTheCalls).containsExactly("no transaction", "no transaction");
    }

    @Test
    void anEventIsNotSentBeforeItsNextAttempt() {
        Order order = insertOrder();
        Instant nextAttempt = START.plusSeconds(60);
        UUID event = insertEvent(order, Destination.ERP, nextAttempt);

        CLOCK.set(nextAttempt.minus(JUST_BEFORE));
        assertThat(relay.relayDue()).as("events claimed before the next attempt").isZero();

        CLOCK.set(nextAttempt);
        assertThat(relay.relayDue()).as("events claimed at the next attempt").isEqualTo(1);
        assertThat(row(event).status()).isEqualTo("SENT");
    }

    @Test
    void outageRecovery() {
        Order order = insertOrder();
        UUID pushEvent = insertDueEvent(order, Destination.PUSH);
        UUID erpEvent = insertDueEvent(order, Destination.ERP);
        erp.failRetryably();

        relay.relayDue();

        assertThat(row(pushEvent).status()).as("push, not held back by the ERP").isEqualTo("SENT");
        assertThat(row(erpEvent)).isEqualTo(EventRow.pending(1, START.plus(ceiling(1)), "ERP is unavailable"));
        assertThat(gauge("outbox_pending")).as("backlog while the ERP is down").isEqualTo(1);
        assertThat(orderCount(order)).as("orders, still confirmed").isEqualTo(1);

        for (int attempts = 1; attempts <= 3; attempts++) {
            Instant nextAttempt = row(erpEvent).nextAttemptAt();
            CLOCK.set(nextAttempt.minus(JUST_BEFORE));
            assertThat(relay.relayDue()).as("events claimed during the backoff").isZero();

            CLOCK.set(nextAttempt);
            assertThat(relay.relayDue()).as("events claimed when the backoff ends").isEqualTo(1);
            assertThat(row(erpEvent)).isEqualTo(
                    EventRow.pending(attempts + 1, nextAttempt.plus(ceiling(attempts + 1)), "ERP is unavailable"));
        }

        erp.recover();
        CLOCK.set(row(erpEvent).nextAttemptAt());
        relay.relayDue();

        assertThat(row(erpEvent)).isEqualTo(EventRow.sent(4, "ERP is unavailable"));
        assertThat(erp.calls(erpEvent)).as("ERP calls, the failed ones included").isEqualTo(5);
        assertThat(erp.order(order.number())).as("ERP order after the recovery").isPresent();
        assertThat(gauge("outbox_pending")).as("backlog after the recovery").isZero();
        assertThat(gauge("outbox_dead")).isZero();
    }

    @Test
    void aFailureTheGatewayDidNotClassifyIsRetried() {
        UUID event = insertDueEvent(insertOrder(), Destination.PUSH);
        doThrow(new IllegalStateException("connection reset")).when(push).send(any());

        relay.relayDue();

        assertThat(row(event)).isEqualTo(
                EventRow.pending(1, START.plus(ceiling(1)), "java.lang.IllegalStateException: connection reset"));
    }

    @Test
    void leaseExpiry() {
        Order order = insertOrder();
        UUID pushEvent = insertDueEvent(order, Destination.PUSH);
        UUID erpEvent = insertDueEvent(order, Destination.ERP);
        Instant leaseEnd = START.plus(properties.lease());

        // A relay leases both events, delivers them and stops before recording anything: a crash between send and mark.
        List<ClaimedEvent> claimed = repository.claimDue(START, leaseEnd, 10);
        assertThat(claimed).as("events claimed by the relay that crashes").hasSize(2);
        claimed.forEach(this::deliverWithoutRecording);
        assertThat(row(erpEvent)).isEqualTo(new EventRow("PENDING", 0, START, leaseEnd, null));

        CLOCK.set(leaseEnd.minus(JUST_BEFORE));
        assertThat(relay.relayDue()).as("events claimed while the lease holds").isZero();

        CLOCK.set(leaseEnd);
        assertThat(relay.relayDue()).as("events claimed once the lease expired").isEqualTo(2);

        assertThat(row(pushEvent)).isEqualTo(EventRow.sent(0, null));
        assertThat(row(erpEvent)).isEqualTo(EventRow.sent(0, null));
        assertThat(push.calls(pushEvent)).as("push calls").isEqualTo(2);
        assertThat(push.effects(pushEvent)).as("push notifications shown, collapsed by event id").isEqualTo(1);
        assertThat(erp.calls(erpEvent)).as("ERP calls").isEqualTo(2);
        assertThat(erp.effects(erpEvent)).as("ERP upserts, deduplicated by event id").isEqualTo(1);
    }

    @Test
    void aClaimSkipsTheEventsAnotherRelayIsClaimingInsteadOfWaitingForThem() throws SQLException {
        Order order = insertOrder();
        UUID busy = insertDueEvent(order, Destination.PUSH);
        UUID free = insertDueEvent(order, Destination.ERP);

        try (Connection otherRelay = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            otherRelay.setAutoCommit(false);
            lockRow(otherRelay, busy);

            int claimed = assertTimeoutPreemptively(CLAIM_LIMIT, relay::relayDue,
                    "the claim waited for an event another relay is claiming");

            assertThat(claimed).as("events claimed").isEqualTo(1);
            otherRelay.rollback();
        }
        assertThat(row(busy)).as("the event the other relay is claiming")
                .isEqualTo(new EventRow("PENDING", 0, START, null, null));
        assertThat(row(free).status()).isEqualTo("SENT");
    }

    @Test
    void aRelayWhoseLeaseWasTakenOverCannotRecordAnOutcome() {
        UUID event = insertDueEvent(insertOrder(), Destination.ERP);
        Instant firstLeaseEnd = START.plus(properties.lease());
        Instant secondLeaseEnd = firstLeaseEnd.plus(properties.lease());
        ClaimedEvent slow = repository.claimDue(START, firstLeaseEnd, 10).getFirst();
        ClaimedEvent takeover = repository.claimDue(firstLeaseEnd, secondLeaseEnd, 10).getFirst();

        assertThat(repository.markDead(slow, 1, "late failure")).as("DEAD recorded with the lost lease").isFalse();
        assertThat(repository.scheduleRetry(slow, 1, secondLeaseEnd, "late failure"))
                .as("retry recorded with the lost lease").isFalse();
        assertThat(repository.markSent(slow)).as("SENT recorded with the lost lease").isFalse();
        assertThat(row(event)).isEqualTo(new EventRow("PENDING", 0, START, secondLeaseEnd, null));

        assertThat(repository.markSent(takeover)).as("SENT recorded with the current lease").isTrue();
        assertThat(row(event)).isEqualTo(EventRow.sent(0, null));
    }

    @Test
    void dead(CapturedOutput output) {
        Order rejected = insertOrder();
        UUID rejectedEvent = insertDueEvent(rejected, Destination.ERP);
        erp.failNonRetryably();

        relay.relayDue();

        assertThat(row(rejectedEvent)).as("a non-retryable failure is final at once")
                .isEqualTo(EventRow.dead(1, "ERP rejected order " + rejected.number()));
        assertThat(gauge("outbox_dead")).isEqualTo(1);

        Order unlucky = insertOrder();
        UUID exhaustedEvent = insertDueEvent(unlucky, Destination.ERP);
        erp.failRetryably();
        for (int attempts = 1; attempts < properties.maxAttempts(); attempts++) {
            relay.relayDue();
            EventRow retrying = row(exhaustedEvent);
            assertThat(retrying.status()).as("status after %d failed attempts", attempts).isEqualTo("PENDING");
            CLOCK.set(retrying.nextAttemptAt());
        }
        relay.relayDue();

        assertThat(row(exhaustedEvent)).as("a retryable failure is final once the attempts run out")
                .isEqualTo(EventRow.dead(properties.maxAttempts(), "ERP is unavailable"));
        assertThat(erp.calls(exhaustedEvent)).as("ERP calls").isEqualTo(properties.maxAttempts());
        assertThat(relay.relayDue()).as("events claimed once dead").isZero();
        assertThat(gauge("outbox_dead")).isEqualTo(2);
        assertThat(gauge("outbox_pending")).isZero();
        assertThat(output).contains("Outbox event " + rejectedEvent + " to ERP is DEAD")
                .contains("Outbox event " + exhaustedEvent + " to ERP is DEAD");
    }

    @Test
    void theBackgroundPollRelaysEveryPollInterval() {
        assertThat(scheduledTasks.getScheduledTasks()).as("scheduled tasks")
                .map(ScheduledTask::getTask)
                .contains(outboxRelayPoll);
        assertThat(outboxRelayPoll.getIntervalDuration()).as("wait between two polls")
                .isEqualTo(properties.pollInterval());
        assertThat(outboxRelayPoll.getInitialDelayDuration()).as("wait before the first poll")
                .isEqualTo(properties.pollInterval());
        UUID event = insertDueEvent(insertOrder(), Destination.PUSH);

        outboxRelayPoll.getRunnable().run();

        assertThat(row(event).status()).isEqualTo("SENT");
    }

    private void deliverWithoutRecording(ClaimedEvent claimed) {
        switch (claimed.destination()) {
            case PUSH -> push.send(claimed.event());
            case ERP -> erp.publish(claimed.event());
        }
    }

    /** Holds the row lock that another relay's claim statement holds while it runs. */
    private static void lockRow(Connection connection, UUID event) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT id FROM outbox_events WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            lock.setString(1, TENANT);
            lock.setObject(2, event);
            lock.executeQuery().close();
        }
    }

    /**
     * What the database shows while a gateway call is in progress. A Spring transaction bound to the relay's thread,
     * or any connection holding a transaction open, would keep the claimed rows locked during someone else's latency.
     */
    private String transactionsOpenNow() {
        boolean bound = TransactionSynchronizationManager.isActualTransactionActive();
        long idleInTransaction = jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND state LIKE 'idle in transaction%'""")
                .query(Long.class)
                .single();
        return bound || idleInTransaction > 0
                ? "bound to the thread: " + bound + ", connections idle in a transaction: " + idleInTransaction
                : "no transaction";
    }

    private static Duration ceiling(int attempts) {
        Duration ceiling = BACKOFF_BASE.multipliedBy(1L << (attempts - 1));
        return ceiling.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : ceiling;
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private record Order(UUID id, String number, UUID quoteId) {
    }

    private record EventRow(String status, int attempts, Instant nextAttemptAt, Instant lockedUntil, String lastError) {

        static EventRow pending(int attempts, Instant nextAttemptAt, String lastError) {
            return new EventRow("PENDING", attempts, nextAttemptAt, null, lastError);
        }

        /** A delivered event. Its next attempt time is irrelevant, so it is not compared. */
        static EventRow sent(int attempts, String lastError) {
            return new EventRow("SENT", attempts, null, null, lastError);
        }

        /** A dead event. Its next attempt time is irrelevant, so it is not compared. */
        static EventRow dead(int attempts, String lastError) {
            return new EventRow("DEAD", attempts, null, null, lastError);
        }
    }

    private Order insertOrder() {
        UUID quote = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quotes
                            (tenant_id, id, customer_id, currency_code, payment_method, created_at, expires_at)
                        VALUES (?, ?, ?, 'COP', 'CASH', ?, ?)""")
                .params(TENANT, quote, CUSTOMER, at(START.minusSeconds(60)), at(START.plusSeconds(840)))
                .update();
        UUID order = UUID.randomUUID();
        String number = "TEST-" + order;
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(TENANT, order, quote, CUSTOMER, number, at(START))
                .update();
        return new Order(order, number, quote);
    }

    /** An event as the order transaction writes it: PENDING, never attempted, due at once. */
    private UUID insertDueEvent(Order order, Destination destination) {
        return insertEvent(order, destination, START);
    }

    private UUID insertEvent(Order order, Destination destination, Instant nextAttemptAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox_events
                            (tenant_id, id, order_id, destination, status, attempts, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)""")
                .params(TENANT, id, order.id(), destination.name(), at(nextAttemptAt), at(START))
                .update();
        return id;
    }

    /** The row, with the next attempt time only while the event is still PENDING, the only time it matters. */
    private EventRow row(UUID event) {
        return jdbc.sql("""
                        SELECT status, attempts, next_attempt_at, locked_until, last_error
                        FROM outbox_events WHERE tenant_id = ? AND id = ?""")
                .params(TENANT, event)
                .query((rs, rowNum) -> {
                    String status = rs.getString("status");
                    Instant nextAttemptAt = instant(rs.getObject("next_attempt_at", OffsetDateTime.class));
                    return new EventRow(status, rs.getInt("attempts"), "PENDING".equals(status) ? nextAttemptAt : null,
                            instant(rs.getObject("locked_until", OffsetDateTime.class)), rs.getString("last_error"));
                })
                .single();
    }

    private long orderCount(Order order) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE tenant_id = ? AND id = ?")
                .params(TENANT, order.id())
                .query(Long.class)
                .single();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
