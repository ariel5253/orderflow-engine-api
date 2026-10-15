package com.orderflow.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-48: housekeeping purges idempotency keys, expired unconfirmed quotes and sent outbox events by their retention,
 * and nothing else. The clock is fixed and the retentions are the configured defaults, 24 hours for keys and 7 days
 * for quotes and events. Each kind of row is seeded one second on each side of its cutoff, which pins both the
 * retention that applies to it and the direction of the comparison. The other timestamps of a key or an event, the
 * confirmation of its order and the event's next attempt, are set to now, far from that cutoff, so only created_at
 * decides. The rows that must never go are seeded a month past every cutoff. Every test runs in a transaction that is
 * rolled back, so no test sees the rows of another.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class HousekeepingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final String CO = "co-alpha";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime KEY_CUTOFF = NOW.minusHours(24);
    private static final OffsetDateTime CUTOFF = NOW.minusDays(7);
    private static final OffsetDateTime LONG_AGO = CUTOFF.minusDays(30);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration QUOTE_TTL = Duration.ofMinutes(15);

    @Autowired
    private HousekeepingJob housekeeping;

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest(name = "tenant {0}")
    @ValueSource(strings = {"co-alpha", "ec-alpha"})
    void anIdempotencyKeyIsPurgedOnceOlderThanItsRetentionAndItsOrderStays(String tenant) {
        Order expiredOrder = insertConfirmedOrder(tenant, NOW);
        String expired = insertIdempotencyKey(tenant, expiredOrder, KEY_CUTOFF.minus(ONE_SECOND));
        Order keptOrder = insertConfirmedOrder(tenant, NOW);
        String kept = insertIdempotencyKey(tenant, keptOrder, KEY_CUTOFF.plus(ONE_SECOND));

        housekeeping.purgeExpired();

        assertThat(idempotencyKeyExists(expired)).as("key one second past its retention").isFalse();
        assertThat(idempotencyKeyExists(kept)).as("key one second within its retention").isTrue();
        assertThat(orderExists(expiredOrder)).as("order of the purged key").isTrue();
    }

    @ParameterizedTest(name = "tenant {0}")
    @ValueSource(strings = {"co-alpha", "ec-alpha"})
    void anUnconfirmedQuoteIsPurgedWithItsLinesAndAdjustmentsOnceExpiredForTheRetention(String tenant) {
        UUID customer = insertCustomer(tenant);
        UUID expired = insertQuote(tenant, customer, CUTOFF.minus(ONE_SECOND));
        UUID kept = insertQuote(tenant, customer, CUTOFF.plus(ONE_SECOND));

        housekeeping.purgeExpired();

        assertThat(quoteRows(expired)).as("quote, lines and adjustments expired one second past the retention")
                .containsExactly(0L, 0L, 0L);
        assertThat(quoteRows(kept)).as("quote, lines and adjustments expired one second within the retention")
                .containsExactly(1L, 1L, 1L);
    }

    @Test
    void aConfirmedQuoteIsNeverPurged() {
        Order order = insertConfirmedOrder(CO, LONG_AGO);
        UUID unconfirmed = insertQuote(CO, order.customer(), LONG_AGO.plus(QUOTE_TTL));

        housekeeping.purgeExpired();

        assertThat(quoteRows(order.quote())).as("quote, lines and adjustments of the confirmed quote")
                .containsExactly(1L, 1L, 1L);
        assertThat(orderExists(order)).as("order of the confirmed quote").isTrue();
        assertThat(quoteRows(unconfirmed)).as("unconfirmed quote of the same age, purged by the same run")
                .containsExactly(0L, 0L, 0L);
    }

    @ParameterizedTest(name = "tenant {0}")
    @ValueSource(strings = {"co-alpha", "ec-alpha"})
    void aSentEventIsPurgedOnceOlderThanTheRetention(String tenant) {
        UUID expired = insertOutboxEvent(tenant, "SENT", CUTOFF.minus(ONE_SECOND));
        UUID kept = insertOutboxEvent(tenant, "SENT", CUTOFF.plus(ONE_SECOND));

        housekeeping.purgeExpired();

        assertThat(outboxEventExists(expired)).as("SENT event one second past the retention").isFalse();
        assertThat(outboxEventExists(kept)).as("SENT event one second within the retention").isTrue();
    }

    @Test
    void deadAndPendingEventsAreNeverPurged() {
        UUID dead = insertOutboxEvent(CO, "DEAD", LONG_AGO);
        UUID pending = insertOutboxEvent(CO, "PENDING", LONG_AGO);
        UUID sent = insertOutboxEvent(CO, "SENT", LONG_AGO);

        housekeeping.purgeExpired();

        assertThat(outboxEventExists(dead)).as("DEAD event, which waits for an operator to requeue it").isTrue();
        assertThat(outboxEventExists(pending)).as("PENDING event, which is still due").isTrue();
        assertThat(outboxEventExists(sent)).as("SENT event of the same age, purged by the same run").isFalse();
    }

    /** A confirmed order with its quote, which was created a minute before the confirmation. */
    private record Order(String tenant, UUID id, UUID quote, UUID customer) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        }
    }

    private UUID insertCustomer(String tenant) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customers (tenant_id, id, name) VALUES (?, ?, 'Test customer')")
                .params(tenant, id)
                .update();
        return id;
    }

    /** A quote with one line and one discount component on it, so the purge must cascade two levels down. */
    private UUID insertQuote(String tenant, UUID customer, OffsetDateTime expiresAt) {
        String currency = jdbc.sql("""
                        SELECT c.currency_code FROM tenants t JOIN countries c ON c.code = t.country_code
                        WHERE t.id = ?""")
                .param(tenant)
                .query(String.class)
                .single();
        UUID product = jdbc.sql("SELECT id FROM products WHERE tenant_id = ? ORDER BY sku LIMIT 1")
                .param(tenant)
                .query(UUID.class)
                .single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quotes (tenant_id, id, customer_id, currency_code, payment_method, created_at, expires_at)
                        VALUES (?, ?, ?, ?, 'CASH', ?, ?)""")
                .params(tenant, id, customer, currency, expiresAt.minus(QUOTE_TTL), expiresAt)
                .update();
        jdbc.sql("""
                        INSERT INTO quote_lines
                            (tenant_id, quote_id, line_no, product_id, qty, unit_price, tax_rate, taxable_base, tax)
                        VALUES (?, ?, 1, ?, 1, 1000, 0.19, 950, 181)""")
                .params(tenant, id, product)
                .update();
        jdbc.sql("""
                        INSERT INTO quote_line_adjustments (tenant_id, quote_id, line_no, kind, rule_id, amount)
                        VALUES (?, ?, 1, 'TIER', ?, 50)""")
                .params(tenant, id, UUID.randomUUID())
                .update();
        return id;
    }

    private Order insertConfirmedOrder(String tenant, OffsetDateTime confirmedAt) {
        UUID customer = insertCustomer(tenant);
        UUID quote = insertQuote(tenant, customer, confirmedAt.minusMinutes(1).plus(QUOTE_TTL));
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(tenant, id, quote, customer, "TEST-" + id, confirmedAt)
                .update();
        return new Order(tenant, id, quote, customer);
    }

    /** A committed key, as the confirmation leaves it: it points at its order and holds the first response. */
    private String insertIdempotencyKey(String tenant, Order order, OffsetDateTime createdAt) {
        String key = "key-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (tenant_id, customer_id, idem_key, request_hash, order_id, response_body, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(tenant, order.customer(), key, new byte[32], order.id(), new byte[] {'{', '}'}, createdAt)
                .update();
        return key;
    }

    /** An event of its own order. The order is confirmed now and the next attempt is due now, whatever createdAt is. */
    private UUID insertOutboxEvent(String tenant, String status, OffsetDateTime createdAt) {
        Order order = insertConfirmedOrder(tenant, NOW);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox_events
                            (tenant_id, id, order_id, destination, status, attempts, next_attempt_at, created_at)
                        VALUES (?, ?, ?, 'ERP', ?, 1, ?, ?)""")
                .params(tenant, id, order.id(), status, NOW, createdAt)
                .update();
        return id;
    }

    private boolean idempotencyKeyExists(String key) {
        return count("SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", key) == 1;
    }

    private boolean orderExists(Order order) {
        return count("SELECT count(*) FROM orders WHERE tenant_id = ? AND id = ?", order.tenant(), order.id()) == 1;
    }

    private boolean outboxEventExists(UUID event) {
        return count("SELECT count(*) FROM outbox_events WHERE id = ?", event) == 1;
    }

    private List<Long> quoteRows(UUID quote) {
        return List.of(
                count("SELECT count(*) FROM quotes WHERE id = ?", quote),
                count("SELECT count(*) FROM quote_lines WHERE quote_id = ?", quote),
                count("SELECT count(*) FROM quote_line_adjustments WHERE quote_id = ?", quote));
    }

    private long count(String sql, Object... values) {
        return jdbc.sql(sql).params(values).query(Long.class).single();
    }
}
