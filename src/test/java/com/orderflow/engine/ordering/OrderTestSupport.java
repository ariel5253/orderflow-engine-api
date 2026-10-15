package com.orderflow.engine.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests, database reads and cleanup shared by the order confirmation tests. The tests commit for real, because a
 * failed confirmation must leave nothing behind and a retry must see what the first request committed, so every test
 * removes its orders and quotes afterwards. Header names, paths and expected values are typed from the contract,
 * never read from the code under test.
 */
public final class OrderTestSupport {

    public static final String ORDERS = "/api/v1/orders";
    public static final String QUOTES = "/api/v1/quotes";
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * Three 1.5 l colas at 5,200 with 19% tax and two chips at 1,800 with 5% tax. No lever of the seeds applies to it:
     * 19,200 of net plus 2,964 and 180 of tax, a total of 22,344.
     */
    public static final String CO_CART = """
            {"paymentMethod": "%s",
             "lines": [{"sku": "SNK-CHIPS-45", "qty": 2}, {"sku": "BEV-COLA-1500", "qty": 3}]}""";

    /** The lines of {@link #CO_CART} as a quote and its order render them, in SKU order. */
    public static final String CO_CART_LINES = """
            [
              {"sku": "BEV-COLA-1500", "description": "Cola 1.5 l", "qty": 3, "isGift": false,
               "unitPrice": "5200", "gross": "15600", "discounts": [], "comboAllocations": [],
               "net": "15600", "taxableBase": "15600", "taxRate": "0.190000", "tax": "2964"},
              {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 2, "isGift": false,
               "unitPrice": "1800", "gross": "3600", "discounts": [], "comboAllocations": [],
               "net": "3600", "taxableBase": "3600", "taxRate": "0.050000", "tax": "180"}
            ]""";

    /** The totals of {@link #CO_CART}. */
    public static final String CO_CART_TOTALS = """
            {"grossSubtotal": "19200", "discounts": [], "gifts": [],
             "taxableBase": "19200", "taxes": "3144", "total": "22344"}""";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    public OrderTestSupport(MockMvcTester mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    /** Quotes {@link #CO_CART} for a co-alpha customer through the quote endpoint, as a client would. */
    public UUID quoteCoCart(UUID customer, String paymentMethod) {
        MvcTestResult created = quote("co-alpha", customer, CO_CART.formatted(paymentMethod));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(created).required("quoteId").stringValue());
    }

    /** Sends a quote request through the quote endpoint, as a client would. */
    public MvcTestResult quote(String tenant, UUID customer, String body) {
        return mvc.post().uri(QUOTES)
                .header(TENANT_HEADER, tenant)
                .header(CUSTOMER_HEADER, customer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    public MvcTestResult confirm(String tenant, UUID customer, String key, String body) {
        return mvc.post().uri(ORDERS)
                .header(TENANT_HEADER, tenant)
                .header(CUSTOMER_HEADER, customer.toString())
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    public MvcTestResult confirm(String tenant, UUID customer, String key, UUID quoteId) {
        return confirm(tenant, customer, key, confirmBody(quoteId));
    }

    public static String confirmBody(UUID quoteId) {
        return "{\"quoteId\": \"" + quoteId + "\"}";
    }

    /** A fresh key of the recommended shape: a UUID, 36 characters. */
    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** The rows a confirmation writes: orders, credit movements, outbox events and idempotency keys. */
    public void assertNothingConfirmed() {
        assertThat(count("orders")).as("orders").isZero();
        assertThat(count("credit_ledger")).as("credit ledger rows").isZero();
        assertThat(count("outbox_events")).as("outbox events").isZero();
        assertThat(count("idempotency_keys")).as("idempotency keys").isZero();
    }

    /** The credit the customer has consumed, as text at the stored scale, e.g. {@code 22344.0000}. */
    public String consumed(String tenant, UUID customer) {
        return jdbc.sql("""
                        SELECT CAST(consumed AS text) FROM credit_accounts
                        WHERE tenant_id = :tenantId AND customer_id = :customerId""")
                .param("tenantId", tenant)
                .param("customerId", customer)
                .query(String.class)
                .single();
    }

    /** The amount of every credit movement, as text at the stored scale, e.g. {@code 22344.0000}. */
    public List<String> ledgerAmounts() {
        return jdbc.sql("SELECT CAST(amount AS text) FROM credit_ledger ORDER BY amount")
                .query(String.class)
                .list();
    }

    public void setConsumed(String tenant, UUID customer, long consumed) {
        jdbc.sql("""
                        UPDATE credit_accounts SET consumed = :consumed
                        WHERE tenant_id = :tenantId AND customer_id = :customerId""")
                .param("consumed", consumed)
                .param("tenantId", tenant)
                .param("customerId", customer)
                .update();
    }

    /**
     * The number the next order of a country gets, read from the country's sequence before the request: the next
     * value of the sequence, after the country code and zero-padded to six digits (api-contract section 1).
     */
    public String nextOrderNumber(String country) {
        long next = jdbc.sql("SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM order_number_seq_"
                        + country.toLowerCase(Locale.ROOT))
                .query(Long.class)
                .single();
        return String.format(Locale.ROOT, "%s-%06d", country, next);
    }

    /** Removes every order, credit movement, event, key and quote, and gives the seeded credit back. */
    public void deleteOrdersAndQuotes() {
        jdbc.sql("DELETE FROM outbox_events").update();
        jdbc.sql("DELETE FROM credit_ledger").update();
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM orders").update();
        jdbc.sql("DELETE FROM quotes").update();
        jdbc.sql("UPDATE credit_accounts SET consumed = 0").update();
    }

    public static JsonNode json(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    public static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    /** A problem+json body with the status, code and type of api-contract section 3, and localized text. */
    public static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = json(result);
        assertThat(problem.required("status").intValue()).isEqualTo(status.value());
        assertThat(problem.required("code").stringValue()).isEqualTo(code);
        assertThat(problem.required("type").stringValue())
                .isEqualTo("https://orderflow-engine.example/problems/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        assertThat(problem.required("instance").stringValue()).isEqualTo(ORDERS);
        assertThat(problem.required("title").stringValue()).isNotBlank();
        assertThat(problem.required("detail").stringValue()).isNotBlank();
        assertThat(problem.required("correlationId").stringValue()).isNotBlank();
    }
}
