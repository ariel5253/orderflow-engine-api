package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.CreditAdvice;
import com.orderflow.engine.quoting.CreditAdvisor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the customer's credit account as it is now, with a plain read: the advisory check takes no lock and reserves
 * nothing (A-08). Only the order confirmation consumes credit, with its conditional update.
 */
@Repository
public class JdbcCreditAdvisor implements CreditAdvisor {

    private static final String CREDIT_ACCOUNT = """
            SELECT credit_limit, consumed
            FROM credit_accounts
            WHERE tenant_id = :tenantId AND customer_id = :customerId
            """;

    private final JdbcClient jdbc;

    public JdbcCreditAdvisor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CreditAdvice> advise(String tenantId, UUID customerId, Money total) {
        CurrencyPolicy currency = total.policy();
        String account = "Credit account of customer " + customerId + " of tenant '" + tenantId + "'";
        return jdbc.sql(CREDIT_ACCOUNT)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .query((rs, row) -> CreditAdvice.of(total,
                        MoneyColumns.read(rs.getBigDecimal("credit_limit"), currency, account + ": limit"),
                        MoneyColumns.read(rs.getBigDecimal("consumed"), currency, account + ": consumed")))
                .optional();
    }
}
