package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.outbox.HousekeepingStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The housekeeping purges with explicit SQL. Each method is one statement, run outside any Spring transaction, so it
 * commits on its own and holds no lock while another purge runs. No statement filters by tenant, so one call covers
 * every tenant, and each is served by an index that does not start with tenant_id.
 */
@Repository
public class JdbcHousekeepingStore implements HousekeepingStore {

    private final JdbcClient jdbc;

    public JdbcHousekeepingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeIdempotencyKeys(Instant cutoff) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < ?")
                .param(at(cutoff))
                .update();
    }

    /**
     * Lines and their discount components go with the quote (ON DELETE CASCADE). The schema restricts the delete of a
     * confirmed quote too, but one violation would abort the whole statement, and no expired quote would ever be purged
     * again, so the statement leaves confirmed quotes out.
     */
    @Override
    public int purgeUnconfirmedQuotes(Instant cutoff) {
        return jdbc.sql("""
                        DELETE FROM quotes q
                        WHERE q.expires_at < ?
                          AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.tenant_id = q.tenant_id AND o.quote_id = q.id)""")
                .param(at(cutoff))
                .update();
    }

    @Override
    public int purgeSentEvents(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox_events WHERE status = 'SENT' AND created_at < ?")
                .param(at(cutoff))
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
