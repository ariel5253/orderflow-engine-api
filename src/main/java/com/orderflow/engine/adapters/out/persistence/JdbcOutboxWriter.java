package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.ordering.OutboxWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the outbox events of a confirmed order with explicit SQL, in the order transaction (data model section 5).
 * Each event starts PENDING, never attempted, unleased and due at once, which is where the outbox relay picks it up.
 */
@Repository
public class JdbcOutboxWriter implements OutboxWriter {

    private static final String ORDER_CONFIRMED = """
            INSERT INTO outbox_events
                (tenant_id, id, order_id, destination, status, attempts, next_attempt_at, created_at)
            VALUES (:tenantId, :pushEventId, :orderId, 'PUSH', 'PENDING', 0, :confirmedAt, :confirmedAt),
                   (:tenantId, :erpEventId, :orderId, 'ERP', 'PENDING', 0, :confirmedAt, :confirmedAt)
            """;

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    public JdbcOutboxWriter(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public void orderConfirmed(String tenantId, UUID orderId, Instant confirmedAt) {
        OffsetDateTime at = confirmedAt.atOffset(ZoneOffset.UTC);
        jdbc.sql(ORDER_CONFIRMED)
                .param("tenantId", tenantId)
                .param("pushEventId", ids.next())
                .param("erpEventId", ids.next())
                .param("orderId", orderId)
                .param("confirmedAt", at)
                .update();
    }
}
