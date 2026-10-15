package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.outbox.ClaimedEvent;
import com.orderflow.engine.outbox.Destination;
import com.orderflow.engine.outbox.OrderConfirmedEvent;
import com.orderflow.engine.outbox.OutboxRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The outbox with explicit SQL. Each method is one statement, run outside any Spring transaction, so it commits on its
 * own: the claim's row locks last only as long as the claim statement itself.
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    /**
     * Picks the oldest due rows that no relay holds, skipping rows another relay is locking right now instead of
     * waiting for them, and leases them in the same statement. A row whose lease expired with no outcome recorded is
     * due again: its relay stopped between send and mark, so the event is sent again. The payload is read from the
     * order, which is never updated after it is written.
     */
    private static final String CLAIM = """
            WITH due AS (
                SELECT tenant_id, id
                FROM outbox_events
                WHERE status = 'PENDING'
                  AND next_attempt_at <= :now
                  AND (locked_until IS NULL OR locked_until <= :now)
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            ), claimed AS (
                UPDATE outbox_events e
                SET locked_until = :leaseUntil
                FROM due
                WHERE e.tenant_id = due.tenant_id AND e.id = due.id
                RETURNING e.tenant_id, e.id, e.order_id, e.destination, e.attempts, e.next_attempt_at, e.locked_until
            )
            SELECT c.tenant_id, c.id, c.destination, c.attempts, c.locked_until,
                   o.id AS order_id, o.order_number, o.customer_id, o.quote_id, o.confirmed_at
            FROM claimed c
            JOIN orders o ON o.tenant_id = c.tenant_id AND o.id = c.order_id
            ORDER BY c.next_attempt_at, c.id
            """;

    /** Only the holder of the current lease may record an outcome, and only once. */
    private static final String HELD_LEASE =
            " WHERE tenant_id = :tenant AND id = :id AND status = 'PENDING' AND locked_until = :leasedUntil";

    private final JdbcClient jdbc;

    public JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ClaimedEvent> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql(CLAIM)
                .param("now", at(now))
                .param("leaseUntil", at(leaseUntil))
                .param("limit", limit)
                .query(JdbcOutboxRepository::claimedEvent)
                .list();
    }

    @Override
    public boolean markSent(ClaimedEvent claimed) {
        return jdbc.sql("UPDATE outbox_events SET status = 'SENT', locked_until = NULL" + HELD_LEASE)
                .params(lease(claimed))
                .update() == 1;
    }

    @Override
    public boolean scheduleRetry(ClaimedEvent claimed, int attempts, Instant nextAttemptAt, String error) {
        return jdbc.sql("""
                        UPDATE outbox_events
                        SET attempts = :attempts, next_attempt_at = :nextAttemptAt, locked_until = NULL,
                            last_error = :error""" + HELD_LEASE)
                .params(lease(claimed))
                .param("attempts", attempts)
                .param("nextAttemptAt", at(nextAttemptAt))
                .param("error", error)
                .update() == 1;
    }

    @Override
    public boolean markDead(ClaimedEvent claimed, int attempts, String error) {
        return jdbc.sql("""
                        UPDATE outbox_events
                        SET status = 'DEAD', attempts = :attempts, locked_until = NULL,
                            last_error = :error""" + HELD_LEASE)
                .params(lease(claimed))
                .param("attempts", attempts)
                .param("error", error)
                .update() == 1;
    }

    @Override
    public long countPending() {
        return count("PENDING");
    }

    @Override
    public long countDead() {
        return count("DEAD");
    }

    private long count(String status) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = ?")
                .param(status)
                .query(Long.class)
                .single();
    }

    private static Map<String, Object> lease(ClaimedEvent claimed) {
        return Map.of(
                "tenant", claimed.event().tenantId(),
                "id", claimed.event().eventId(),
                "leasedUntil", at(claimed.leasedUntil()));
    }

    private static ClaimedEvent claimedEvent(ResultSet rs, int rowNum) throws SQLException {
        OrderConfirmedEvent event = new OrderConfirmedEvent(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getObject("order_id", UUID.class),
                rs.getString("order_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("quote_id", UUID.class),
                rs.getObject("confirmed_at", OffsetDateTime.class).toInstant());
        return new ClaimedEvent(event, Destination.valueOf(rs.getString("destination")), rs.getInt("attempts"),
                rs.getObject("locked_until", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
