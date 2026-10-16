package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.ordering.IdempotencyStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys with explicit SQL. The key row is the first lock of a confirmation (data model section 8): its
 * primary key entry stays uncommitted until the confirmation ends, so a concurrent request with the same key waits on
 * it, then finds either the completed answer or a free key.
 */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String CLAIM = """
            INSERT INTO idempotency_keys (tenant_id, customer_id, idem_key, request_hash, created_at)
            VALUES (:tenantId, :customerId, :key, :requestHash, :createdAt)
            ON CONFLICT (tenant_id, customer_id, idem_key) DO NOTHING
            """;

    /**
     * Read in a statement of its own, after the insert: under READ COMMITTED it sees the row that a concurrent request
     * committed while the insert waited for it.
     */
    private static final String COMPLETED = """
            SELECT request_hash, order_id, response_body
            FROM idempotency_keys
            WHERE tenant_id = :tenantId AND customer_id = :customerId AND idem_key = :key
            """;

    private static final String COMPLETE = """
            UPDATE idempotency_keys SET order_id = :orderId, response_body = :responseBody
            WHERE tenant_id = :tenantId AND customer_id = :customerId AND idem_key = :key
            """;

    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CompletedRequest> claim(String tenantId, UUID customerId, String key, byte[] requestHash,
            Instant createdAt) {
        int inserted = LockWaits.bounded("Idempotency key '" + key + "'", () -> jdbc.sql(CLAIM)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update());
        if (inserted == 1) {
            return Optional.empty();
        }
        // Failures are rolled back with their key, so a key that is committed always holds a completed request.
        return Optional.of(jdbc.sql(COMPLETED)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .param("key", key)
                .query((rs, row) -> new CompletedRequest(rs.getBytes("request_hash"),
                        rs.getObject("order_id", UUID.class), rs.getBytes("response_body")))
                .single());
    }

    @Override
    public void complete(String tenantId, UUID customerId, String key, UUID orderId, byte[] responseBody) {
        int updated = jdbc.sql(COMPLETE)
                .param("orderId", orderId)
                .param("responseBody", responseBody)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .param("key", key)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency key '" + key + "' of customer " + customerId + " of tenant '"
                    + tenantId + "' is not held by this transaction");
        }
    }
}
