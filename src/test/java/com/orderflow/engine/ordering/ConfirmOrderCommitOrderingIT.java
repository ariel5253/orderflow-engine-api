package com.orderflow.engine.ordering;

import static com.orderflow.engine.ordering.OrderTestSupport.ORDERS;
import static com.orderflow.engine.ordering.OrderTestSupport.REPLAYED_HEADER;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * I-201, RF-7 and SPEC section 7.6 ("response sent before the commit"): the answer to a confirmation is written only
 * after the transaction that inserted its order committed. When the first byte of the 201 is written, no transaction
 * is open on the request thread and another connection already reads the order it names. When the commit itself
 * fails, no 201 is ever written: the answer is a 500, nothing is persisted and the key is released.
 *
 * <p>A probe wraps the response of every confirmation and records each status written and, at the first write, what
 * another connection reads. The commit is made to fail by a deferred constraint trigger, which PostgreSQL runs at
 * COMMIT. Confirmations commit for real, so each test removes its trigger, orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ConfirmOrderCommitOrderingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AnswerProbe probe;

    private OrderTestSupport orders;

    @BeforeEach
    void setUp() {
        orders = new OrderTestSupport(mvc, jdbc);
        probe.clear();
    }

    @AfterEach
    void removeTriggerOrdersAndQuotes() {
        stopFailingAtCommit();
        orders.deleteOrdersAndQuotes();
    }

    @Test
    void the201IsWrittenOnlyOnceItsOrderIsCommitted() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");

        MvcTestResult confirmed = orders.confirm(CO, CO_CREDIT_CUSTOMER, newKey(), quoteId);

        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        FirstWrite first = probe.firstWrite();
        assertThat(first.transactionOpen()).as("a transaction is open when the answer is written").isFalse();
        assertThat(first.committedOrderNumbers())
                .as("orders another connection reads when the answer is written")
                .containsExactly(json(confirmed).required("orderNumber").stringValue());
        assertThat(probe.statuses()).as("statuses written").containsExactly(HttpStatus.CREATED.value());
    }

    @Test
    void aConfirmationWhoseCommitFailsIsNeverAnswered201() {
        UUID quoteId = orders.quoteCoCart(CO_CREDIT_CUSTOMER, "CREDIT");
        String key = newKey();
        failAtCommit();

        assertProblem(orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR");

        assertThat(probe.statuses()).as("statuses written").containsExactly(HttpStatus.INTERNAL_SERVER_ERROR.value());
        orders.assertNothingConfirmed();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("0.0000");

        stopFailingAtCommit();
        MvcTestResult retry = orders.confirm(CO, CO_CREDIT_CUSTOMER, key, quoteId);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).headers().doesNotContainHeader(REPLAYED_HEADER);
        assertThat(orders.count("orders")).as("orders").isOne();
        assertThat(orders.consumed(CO, CO_CREDIT_CUSTOMER)).as("consumed credit").isEqualTo("22344.0000");
    }

    /** Every statement of the confirmation succeeds; the error is raised by the COMMIT itself. */
    private void failAtCommit() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION injected_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Failure injected at the commit of an insert into %', TG_TABLE_NAME;
                END
                $$""").update();
        jdbc.sql("""
                CREATE CONSTRAINT TRIGGER injected_commit_failure AFTER INSERT ON orders
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION injected_commit_failure()""").update();
    }

    private void stopFailingAtCommit() {
        jdbc.sql("DROP TRIGGER IF EXISTS injected_commit_failure ON orders").update();
    }

    /**
     * What the probe saw when the answer to a confirmation started to be written: whether the request thread still had
     * a transaction open, and the numbers of the orders that another connection could read.
     */
    record FirstWrite(boolean transactionOpen, List<String> committedOrderNumbers) {
    }

    /**
     * Wraps the response of every confirmation. It records each status written, and at the first write of a status or
     * a body, what a connection other than the request's reads. The read runs on another thread, which shares no
     * transaction with the request, so under READ COMMITTED it sees committed rows only.
     */
    static final class AnswerProbe extends OncePerRequestFilter {

        private final JdbcClient jdbc;
        private final List<Integer> statuses = new CopyOnWriteArrayList<>();
        private final List<FirstWrite> firstWrites = new CopyOnWriteArrayList<>();

        AnswerProbe(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        void clear() {
            statuses.clear();
            firstWrites.clear();
        }

        List<Integer> statuses() {
            return List.copyOf(statuses);
        }

        FirstWrite firstWrite() {
            assertThat(firstWrites).as("first writes of the answer").hasSize(1);
            return firstWrites.getFirst();
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !(HttpMethod.POST.matches(request.getMethod()) && ORDERS.equals(request.getRequestURI()));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            chain.doFilter(request, new ProbedResponse(response));
        }

        private FirstWrite observe() {
            boolean transactionOpen = TransactionSynchronizationManager.isActualTransactionActive();
            List<String> committed = CompletableFuture.supplyAsync(() -> jdbc.sql("SELECT order_number FROM orders")
                    .query(String.class)
                    .list()).join();
            return new FirstWrite(transactionOpen, committed);
        }

        private final class ProbedResponse extends HttpServletResponseWrapper {

            private boolean written;

            ProbedResponse(HttpServletResponse response) {
                super(response);
            }

            @Override
            public void setStatus(int status) {
                statuses.add(status);
                firstWrite();
                super.setStatus(status);
            }

            @Override
            public ServletOutputStream getOutputStream() throws IOException {
                firstWrite();
                return super.getOutputStream();
            }

            @Override
            public PrintWriter getWriter() throws IOException {
                firstWrite();
                return super.getWriter();
            }

            @Override
            public void flushBuffer() throws IOException {
                firstWrite();
                super.flushBuffer();
            }

            private void firstWrite() {
                if (!written) {
                    written = true;
                    firstWrites.add(observe());
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {

        @Bean
        AnswerProbe answerProbe(JdbcClient jdbc) {
            return new AnswerProbe(jdbc);
        }
    }
}
