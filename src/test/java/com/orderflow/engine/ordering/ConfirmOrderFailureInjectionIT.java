package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-29, AC-30 and SPEC section 7.6: a confirmation is one transaction, and the client learns its order only from
 * what that transaction committed.
 *
 * <ul>
 *   <li>A failure injected at any write of a credit confirmation, from the credit consumption to the response stored
 *       under the key, the last statement before the commit, answers 500 and leaves no order, no credit movement, no
 *       outbox event, no key and no consumed credit. The same key then confirms the order, so the failure released
 *       it. A confirmation that ran outside its transaction (a bypassed proxy) would keep its first writes, and an
 *       order written after the answer would not turn the answer into a 500.</li>
 *   <li>When the answer is lost after the commit, as a crash or a reset connection loses it, the retry with the same
 *       key gets the committed order, with the number drawn for it, and nothing is confirmed twice.</li>
 * </ul>
 *
 * <p>Failures are injected by a database trigger that raises an error, so the code under test runs unchanged.
 * Confirmations commit for real, so each test removes its triggers, orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ConfirmOrderFailureInjectionIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** The tables a credit confirmation writes after it claims its key, in the order it writes them. */
    private static final List<String> WRITTEN_TABLES =
            List.of("credit_accounts", "orders", "credit_ledger", "outbox_events", "idempotency_keys");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LostAnswerFilter lostAnswers;

    private OrderTestSupport orders;

    @BeforeEach
    void setUp() {
        orders = new OrderTestSupport(mvc, jdbc);
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Failure injected on % of %', TG_OP, TG_TABLE_NAME;
                END
                $$""").update();
    }

    @AfterEach
    void removeTriggersOrdersAndQuotes() {
        WRITTEN_TABLES.forEach(this::stopFailing);
        orders.deleteOrdersAndQuotes();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "UPDATE, credit_accounts",
        "INSERT, orders",
        "INSERT, credit_ledger",
        "INSERT, outbox_events",
        "UPDATE, idempotency_keys"
    })
    void aFailureBeforeTheCommitPersistsNothingAndReleasesTheKey(String operation, String table) {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        failOn(operation, table);

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR");

        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("0.0000");

        stopFailing(table);
        MvcTestResult retry = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).headers().doesNotContainHeader(REPLAYED_HEADER);
        assertConfirmedOnce();
    }

    @Test
    void aRetryAfterTheAnswerIsLostPastTheCommitGetsTheCommittedOrder() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        String orderNumber = orders.nextOrderNumber("CO");
        lostAnswers.loseTheNextAnswer();

        MvcTestResult lost = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);

        assertThat(lost).as("the client receives no answer").hasFailed();
        UUID orderId = jdbc.sql("SELECT id FROM orders").query(UUID.class).single();

        MvcTestResult retry = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).headers().hasValue(REPLAYED_HEADER, "true");
        assertThat(json(retry).required("orderNumber").stringValue()).isEqualTo(orderNumber);
        assertThat(json(retry).required("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(retry.getResponse().getContentAsByteArray())
                .as("the answer stored under the key when the order was committed")
                .isEqualTo(jdbc.sql("SELECT response_body FROM idempotency_keys").query(byte[].class).single());
        assertConfirmedOnce();
    }

    /** {@link OrderTestSupport#CO_CART} is confirmed once, on credit: its 22,344 are consumed once. */
    private void assertConfirmedOnce() {
        assertThat(orders.count("orders")).as("orders").isOne();
        assertThat(orders.count("credit_ledger")).as("credit ledger rows").isOne();
        assertThat(orders.count("outbox_events")).as("outbox events").isEqualTo(2);
        assertThat(orders.count("idempotency_keys")).as("idempotency keys").isOne();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("22344.0000");
    }

    private void failOn(String operation, String table) {
        jdbc.sql("CREATE TRIGGER injected_failure BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION injected_failure()").update();
    }

    private void stopFailing(String table) {
        jdbc.sql("DROP TRIGGER IF EXISTS injected_failure ON " + table).update();
    }

    /**
     * Loses the answer to the next request after the application has handled it, as a crash or a reset connection
     * right after the commit would: the request fails and its body never reaches the client.
     */
    static final class LostAnswerFilter extends OncePerRequestFilter {

        private final AtomicBoolean armed = new AtomicBoolean();

        void loseTheNextAnswer() {
            armed.set(true);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!armed.getAndSet(false)) {
                chain.doFilter(request, response);
                return;
            }
            chain.doFilter(request, new ContentCachingResponseWrapper(response));
            throw new IOException("Connection reset before the answer reached the client");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LostAnswers {

        @Bean
        LostAnswerFilter lostAnswerFilter() {
            return new LostAnswerFilter();
        }
    }
}
