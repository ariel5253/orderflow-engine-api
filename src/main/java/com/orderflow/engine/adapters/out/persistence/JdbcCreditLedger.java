package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.ordering.CreditLedger;
import com.orderflow.engine.pricing.domain.Money;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The credit account and its ledger with explicit SQL (SPEC BR-06). Consumption is one conditional update, the last
 * lock of a confirmation: it locks the account row, and its condition is evaluated on the latest committed consumption,
 * so two confirmations can never consume together more than the limit. The CHECK on the table still holds the limit if
 * this code is ever wrong.
 */
@Repository
public class JdbcCreditLedger implements CreditLedger {

    private static final String CONSUME = """
            UPDATE credit_accounts SET consumed = consumed + :amount
            WHERE tenant_id = :tenantId AND customer_id = :customerId AND consumed + :amount <= credit_limit
            """;

    private static final String RECORD = """
            INSERT INTO credit_ledger (tenant_id, order_id, amount) VALUES (:tenantId, :orderId, :amount)
            """;

    private final JdbcClient jdbc;

    public JdbcCreditLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean consume(String tenantId, UUID customerId, Money amount) {
        return LockWaits.bounded("Credit account of customer " + customerId, () -> jdbc.sql(CONSUME)
                .param("amount", amount.amount())
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .update()) == 1;
    }

    @Override
    public void record(String tenantId, UUID orderId, Money amount) {
        jdbc.sql(RECORD)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("amount", amount.amount())
                .update();
    }
}
