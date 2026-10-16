package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteTestData;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-01 and api-contract E-01, E-02: a quote returns every line and total field of SPEC section 8, with money as
 * strings at the currency scale; it reads back with the same body and its derived status; and every error of the two
 * operation cards is a problem with its status and code. Every test runs in a transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class QuoteContractIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUOTES = "/api/v1/quotes";
    private static final Duration QUOTE_TTL = Duration.ofMinutes(15);

    /** Three 1.5 l colas at 5,200 and two chips at 1,800: 19,200 plus 3,144 of tax, 22,344. */
    private static final String CO_CREDIT_CART = """
            {"paymentMethod": "CREDIT",
             "lines": [{"sku": "SNK-CHIPS-45", "qty": 2}, {"sku": "BEV-COLA-1500", "qty": 3}]}""";

    private static final String CO_CART_LINES = """
            [
              {"sku": "BEV-COLA-1500", "description": "Gaseosa cola 1,5 l", "qty": 3, "isGift": false,
               "unitPrice": "5200", "gross": "15600", "discounts": [], "comboAllocations": [],
               "net": "15600", "taxableBase": "15600", "taxRate": "0.190000", "tax": "2964"},
              {"sku": "SNK-CHIPS-45", "description": "Papas fritas 45 g", "qty": 2, "isGift": false,
               "unitPrice": "1800", "gross": "3600", "discounts": [], "comboAllocations": [],
               "net": "3600", "taxableBase": "3600", "taxRate": "0.050000", "tax": "180"}
            ]""";

    private static final String CO_CART_TOTALS = """
            {"grossSubtotal": "19200", "discounts": [], "gifts": [],
             "taxableBase": "19200", "taxes": "3144", "total": "22344"}""";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private QuoteRepository quotes;

    @Test
    void aCreditQuoteReturnsEveryLineAndTotalFieldOfTheBreakdown() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MvcTestResult result = post(CO, CO_CREDIT_CUSTOMER, CO_CREDIT_CART);
        Instant after = Instant.now();

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        JsonNode body = body(result);
        String quoteId = body.required("quoteId").stringValue();
        String expiresAt = body.required("expiresAt").stringValue();
        assertThat(result).headers().hasValue(HttpHeaders.LOCATION, QUOTES + "/" + quoteId);
        assertThat(Instant.parse(expiresAt)).isBetween(before.plus(QUOTE_TTL), after.plus(QUOTE_TTL));
        assertThat(body).isEqualTo(JSON.readTree("""
                {"quoteId": "%s", "status": "OPEN", "expiresAt": "%s", "currency": "COP",
                 "lines": %s,
                 "totals": %s,
                 "credit": {"fits": true, "available": "500000", "shortfall": "0"}}"""
                .formatted(quoteId, expiresAt, CO_CART_LINES, CO_CART_TOTALS)));
    }

    @Test
    void aQuoteReadsBackWithTheBodyItWasCreatedWith() {
        MvcTestResult created = post(CO, CO_CREDIT_CUSTOMER, CO_CREDIT_CART);

        MvcTestResult read = get(CO, CO_CREDIT_CUSTOMER, quoteIdOf(created));

        assertThat(read).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(body(read)).isEqualTo(body(created));
    }

    @Test
    void aCashQuoteCarriesNoCreditCheck() {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH",
                 "lines": [{"sku": "SNK-CHIPS-45", "qty": 2}, {"sku": "BEV-COLA-1500", "qty": 3}]}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode body = body(result);
        assertThat(body.has("credit")).as("credit field of a CASH quote").isFalse();
        assertThat(body.required("lines")).isEqualTo(JSON.readTree(CO_CART_LINES));
        assertThat(body.required("totals")).isEqualTo(JSON.readTree(CO_CART_TOTALS));
    }

    @Test
    void moneyIsAStringAtTheScaleOfTheTenantCurrency() {
        MvcTestResult result = post(EC, EC_CASH_CUSTOMER, """
                {"paymentMethod": "CASH",
                 "lines": [{"sku": "BEV-WATER-600", "qty": 3}, {"sku": "BEV-COLA-1500", "qty": 2}]}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode body = body(result);
        assertThat(body.required("currency").stringValue()).isEqualTo("USD");
        assertThat(body.required("lines")).isEqualTo(JSON.readTree("""
                [
                  {"sku": "BEV-COLA-1500", "description": "Gaseosa cola 1,5 l", "qty": 2, "isGift": false,
                   "unitPrice": "1.45", "gross": "2.90", "discounts": [], "comboAllocations": [],
                   "net": "2.90", "taxableBase": "2.90", "taxRate": "0.150000", "tax": "0.44"},
                  {"sku": "BEV-WATER-600", "description": "Agua 600 ml", "qty": 3, "isGift": false,
                   "unitPrice": "0.45", "gross": "1.35", "discounts": [], "comboAllocations": [],
                   "net": "1.35", "taxableBase": "1.35", "taxRate": "0.150000", "tax": "0.20"}
                ]"""));
        assertThat(body.required("totals")).isEqualTo(JSON.readTree("""
                {"grossSubtotal": "4.25", "discounts": [], "gifts": [],
                 "taxableBase": "4.25", "taxes": "0.64", "total": "4.89"}"""));
    }

    @Test
    void whileOpenTheCreditCheckFollowsTheCustomersCurrentCredit() {
        String quoteId = quoteIdOf(post(CO, CO_CREDIT_CUSTOMER, CO_CREDIT_CART));
        jdbc.sql("UPDATE credit_accounts SET consumed = 490000 WHERE tenant_id = :tenantId AND customer_id = :id")
                .param("tenantId", CO)
                .param("id", CO_CREDIT_CUSTOMER)
                .update();

        JsonNode body = body(get(CO, CO_CREDIT_CUSTOMER, quoteId));

        assertThat(body.required("status").stringValue()).isEqualTo("OPEN");
        assertThat(body.required("credit")).isEqualTo(JSON.readTree("""
                {"fits": false, "available": "10000", "shortfall": "12344"}"""));
    }

    @Test
    void aConfirmedQuoteReadsAsConfirmedWithNoCreditCheck() {
        String quoteId = quoteIdOf(post(CO, CO_CREDIT_CUSTOMER, CO_CREDIT_CART));
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (:tenantId, :orderId, :quoteId, :customerId, 'CO-000001', now())""")
                .param("tenantId", CO)
                .param("orderId", UUID.randomUUID())
                .param("quoteId", UUID.fromString(quoteId))
                .param("customerId", CO_CREDIT_CUSTOMER)
                .update();

        JsonNode body = body(get(CO, CO_CREDIT_CUSTOMER, quoteId));

        assertThat(body.required("status").stringValue()).isEqualTo("CONFIRMED");
        assertThat(body.has("credit")).as("credit field of a confirmed quote").isFalse();
    }

    @Test
    void anExpiredQuoteReadsAsExpiredWithNoCreditCheck() {
        UUID quoteId = quotes.insert(QuoteTestData.threeColas(CO_CREDIT_CUSTOMER, PaymentMethod.CREDIT,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:15:00Z")));

        JsonNode body = body(get(CO, CO_CREDIT_CUSTOMER, quoteId.toString()));

        assertThat(body.required("status").stringValue()).isEqualTo("EXPIRED");
        assertThat(body.required("expiresAt").stringValue()).isEqualTo("2026-01-01T00:15:00Z");
        assertThat(body.has("credit")).as("credit field of an expired quote").isFalse();
    }

    @Test
    void aQuoteShowsItsDiscountComponentsAndGiftLines() {
        UUID quoteId = quotes.insert(QuoteTestData.withEveryComponent(CO_CASH_CUSTOMER,
                Instant.parse("2100-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:15:00Z")));

        MvcTestResult result = get(CO, CO_CASH_CUSTOMER, quoteId.toString());

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(body(result)).isEqualTo(JSON.readTree("""
                {"quoteId": "%s", "status": "OPEN", "expiresAt": "2100-01-01T00:15:00Z", "currency": "COP",
                 "lines": [
                   {"sku": "BEV-COLA-350", "description": "Gaseosa cola 350 ml", "qty": 30, "isGift": false,
                    "unitPrice": "2500", "gross": "75000",
                    "discounts": [{"origin": "TIER:01a13cdb-cc00-7001-8003-000000000001", "amount": "125"}],
                    "comboAllocations": [{"comboId": "01a13cdb-cc00-7001-8004-000000000001", "amount": "13235"}],
                    "net": "61640", "taxableBase": "61640", "taxRate": "0.190000", "tax": "11712"},
                   {"sku": "SNK-CHIPS-45", "description": "Papas fritas 45 g", "qty": 10, "isGift": false,
                    "unitPrice": "1800", "gross": "18000", "discounts": [],
                    "comboAllocations": [{"comboId": "01a13cdb-cc00-7001-8004-000000000001", "amount": "4765"}],
                    "net": "13235", "taxableBase": "13235", "taxRate": "0.050000", "tax": "662"},
                   {"sku": "BEV-WATER-600", "description": "Agua 600 ml", "qty": 1, "isGift": true,
                    "origin": "GIFT:01a13cdb-cc00-7001-8005-000000000001",
                    "unitPrice": "0", "gross": "0", "discounts": [], "comboAllocations": [],
                    "net": "0", "referenceValue": "1500", "taxableBase": "1500", "taxRate": "0.190000", "tax": "285"}
                 ],
                 "totals": {
                   "grossSubtotal": "93000",
                   "discounts": [{"origin": "COMBO:01a13cdb-cc00-7001-8004-000000000001", "amount": "18000"},
                                 {"origin": "TIER:01a13cdb-cc00-7001-8003-000000000001", "amount": "125"}],
                   "gifts": [{"sku": "BEV-WATER-600", "qty": 1,
                              "origin": "GIFT:01a13cdb-cc00-7001-8005-000000000001"}],
                   "taxableBase": "76375", "taxes": "12659", "total": "87534"}}""".formatted(quoteId)));
    }

    @Test
    void aQuoteOfAnotherCustomerOrTenantIsNotFound() {
        String quoteId = quoteIdOf(post(CO, CO_CREDIT_CUSTOMER, CO_CREDIT_CART));

        assertProblem(get(CO, CO_CASH_CUSTOMER, quoteId), HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND");
        assertProblem(get(EC, EC_CREDIT_CUSTOMER, quoteId), HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND");
        assertProblem(get(EC, CO_CREDIT_CUSTOMER, quoteId), HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND");
        assertProblem(get(CO, CO_CREDIT_CUSTOMER, UUID.randomUUID().toString()), HttpStatus.NOT_FOUND,
                "QUOTE_NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createErrors")
    void everyErrorOfTheCreateOperationIsAProblem(String code, HttpStatus status, String tenant, UUID customer,
            String body) {
        assertProblem(post(tenant, customer, body), status, code);
    }

    /** The rows of the E-01 error table, one request each. */
    static Stream<Arguments> createErrors() {
        return Stream.of(
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, CO_CASH_CUSTOMER,
                        "{\"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}]}"),
                arguments("CART_EMPTY", HttpStatus.BAD_REQUEST, CO, CO_CASH_CUSTOMER,
                        "{\"paymentMethod\": \"CASH\", \"lines\": []}"),
                arguments("INVALID_QUANTITY", HttpStatus.BAD_REQUEST, CO, CO_CASH_CUSTOMER,
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": 2.5}]}"),
                arguments("TENANT_NOT_FOUND", HttpStatus.BAD_REQUEST, "zz-unknown", CO_CASH_CUSTOMER,
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}]}"),
                arguments("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, CO, UUID.randomUUID(),
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}]}"),
                arguments("UNKNOWN_SKU", HttpStatus.UNPROCESSABLE_CONTENT, CO, CO_CASH_CUSTOMER,
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"NO-SUCH-SKU\", \"qty\": 1}]}"),
                arguments("CREDIT_NOT_ENABLED", HttpStatus.UNPROCESSABLE_CONTENT, CO, CO_CASH_CUSTOMER,
                        "{\"paymentMethod\": \"CREDIT\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}]}"));
    }

    private MvcTestResult post(String tenant, UUID customer, String body) {
        return mvc.post().uri(QUOTES)
                .header(QuoteController.TENANT_HEADER, tenant)
                .header(QuoteController.CUSTOMER_HEADER, customer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult get(String tenant, UUID customer, String quoteId) {
        return mvc.get().uri(QUOTES + "/{quoteId}", quoteId)
                .header(QuoteController.TENANT_HEADER, tenant)
                .header(QuoteController.CUSTOMER_HEADER, customer.toString())
                .exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = body(result);
        assertThat(problem.required("status").intValue()).isEqualTo(status.value());
        assertThat(problem.required("code").stringValue()).isEqualTo(code);
        assertThat(problem.required("type").stringValue())
                .isEqualTo("https://orderflow-engine.example/problems/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        assertThat(problem.required("instance").stringValue()).isEqualTo(result.getRequest().getRequestURI());
        assertThat(problem.required("title").stringValue()).isNotBlank();
        assertThat(problem.required("detail").stringValue()).isNotBlank();
        assertThat(problem.required("correlationId").stringValue()).isNotBlank();
    }

    private static String quoteIdOf(MvcTestResult created) {
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return body(created).required("quoteId").stringValue();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
