package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.ordering.OrderRepository;
import com.orderflow.engine.ordering.PlacedOrder;
import com.orderflow.engine.ordering.StoredOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Orders with explicit SQL and explicit locks (data model section 8). An order row holds its number and its quote,
 * and no amount: the breakdown is read from the quote it points at.
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    /** {@code is_local = true}: the setting ends with the current transaction, so it never leaks to another one. */
    private static final String LIMIT_LOCK_WAITS = "SELECT set_config('lock_timeout', :timeout, true)";

    private static final String LOCK_QUOTE = """
            SELECT id FROM quotes
            WHERE tenant_id = :tenantId AND id = :quoteId AND customer_id = :customerId
            FOR UPDATE
            """;

    private static final String ORDER_OF_QUOTE = """
            SELECT id, order_number FROM orders WHERE tenant_id = :tenantId AND quote_id = :quoteId
            """;

    /**
     * Draws the next value of the sequence of the tenant's country, {@code order_number_seq_<country>}. A sequence name
     * cannot be a bind parameter, so it is built from the country code and resolved with a cast to regclass.
     */
    private static final String NEXT_NUMBER = """
            SELECT country_code, nextval(CAST('order_number_seq_' || lower(country_code) AS regclass)) AS number
            FROM tenants
            WHERE id = :tenantId
            """;

    private static final String INSERT_ORDER = """
            INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
            VALUES (:tenantId, :orderId, :quoteId, :customerId, :orderNumber, :confirmedAt)
            """;

    /** Filtered by tenant and customer, so an order of anyone else is never found (BD-01). */
    private static final String FIND_ORDER = """
            SELECT id, order_number, quote_id, confirmed_at FROM orders
            WHERE tenant_id = :tenantId AND id = :orderId AND customer_id = :customerId
            """;

    /**
     * Served by the history index (tenant_id, customer_id, confirmed_at DESC). The id breaks a tie between orders of
     * the same instant, so the order of the rows never depends on the plan.
     */
    private static final String HISTORY = """
            SELECT id, order_number, quote_id, confirmed_at FROM orders
            WHERE tenant_id = :tenantId AND customer_id = :customerId
            ORDER BY confirmed_at DESC, id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    public JdbcOrderRepository(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public void limitLockWaits(Duration timeout) {
        jdbc.sql(LIMIT_LOCK_WAITS)
                .param("timeout", timeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }

    @Override
    public boolean lockQuote(String tenantId, UUID customerId, UUID quoteId) {
        return LockWaits.bounded("Quote " + quoteId, () -> jdbc.sql(LOCK_QUOTE)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .param("customerId", customerId)
                .query(UUID.class)
                .optional()
                .isPresent());
    }

    @Override
    public Optional<PlacedOrder> findByQuote(String tenantId, UUID quoteId) {
        return jdbc.sql(ORDER_OF_QUOTE)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((rs, row) -> new PlacedOrder(rs.getObject("id", UUID.class), rs.getString("order_number")))
                .optional();
    }

    @Override
    public PlacedOrder insert(String tenantId, UUID customerId, UUID quoteId, Instant confirmedAt) {
        // Formatted here rather than in SQL: lpad would cut a seventh digit, where %06d only pads to six.
        String orderNumber = jdbc.sql(NEXT_NUMBER)
                .param("tenantId", tenantId)
                .query((rs, row) -> String.format(Locale.ROOT, "%s-%06d", rs.getString("country_code"),
                        rs.getLong("number")))
                .single();
        UUID orderId = ids.next();
        jdbc.sql(INSERT_ORDER)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("quoteId", quoteId)
                .param("customerId", customerId)
                .param("orderNumber", orderNumber)
                .param("confirmedAt", confirmedAt.atOffset(ZoneOffset.UTC))
                .update();
        return new PlacedOrder(orderId, orderNumber);
    }

    @Override
    public Optional<StoredOrder> find(String tenantId, UUID customerId, UUID orderId) {
        return jdbc.sql(FIND_ORDER)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("customerId", customerId)
                .query((rs, row) -> stored(rs))
                .optional();
    }

    @Override
    public List<StoredOrder> history(String tenantId, UUID customerId, int limit) {
        return jdbc.sql(HISTORY)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .param("limit", limit)
                .query((rs, row) -> stored(rs))
                .list();
    }

    private static StoredOrder stored(ResultSet rs) throws SQLException {
        return new StoredOrder(rs.getObject("id", UUID.class), rs.getString("order_number"),
                rs.getObject("quote_id", UUID.class), rs.getObject("confirmed_at", OffsetDateTime.class).toInstant());
    }
}
