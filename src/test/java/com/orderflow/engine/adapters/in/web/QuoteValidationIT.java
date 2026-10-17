package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-14 and SPEC BR-00: a quantity that is not a whole number from 1 to 1,000,000, also after the lines of a repeated
 * SKU are merged, or a gift quantity a rule derives above 1,000,000, is 400 INVALID_QUANTITY; an empty cart is 400
 * CART_EMPTY, and a SKU the tenant does not sell is 422 UNKNOWN_SKU. A malformed request, an unknown tenant or
 * customer, and a credit quote without a credit account fail with their own codes. A rejected request stores nothing.
 * Every test runs in a transaction that is rolled back, so the database starts with no quote.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class QuoteValidationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final String QUOTES = "/api/v1/quotes";

    /** The seeded co-alpha products of the gift rule a test adds: the 1.5 l cola and the 600 ml water. */
    private static final UUID CO_COLA_1500 = UUID.fromString("01a13cdb-cc00-7001-8002-000000000002");
    private static final UUID CO_WATER_600 = UUID.fromString("01a13cdb-cc00-7001-8002-000000000004");
    private static final UUID TEST_GIFT_RULE = UUID.fromString("01a13cdb-cc00-7001-8005-0000000000f1");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest(name = "qty {0}")
    @ValueSource(strings = {"0", "-1", "0.5", "2.5", "1e-3", "1000001", "2147483648", "9223372036854775808"})
    void aQuantityThatIsNotAWholeNumberFromOneToTheBoundIsInvalid(String qty) {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER,
                "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": " + qty + "}]}");

        assertRejected(result, HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
    }

    @Test
    void aQuantityOfExactlyTheBoundIsQuoted() {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [{"sku": "BEV-COLA-1500", "qty": 1000000}]}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.lines[0].qty").isEqualTo(1_000_000);
    }

    @Test
    void aQuantityIsCheckedAgainstTheBoundAfterRepeatedSkusAreMerged() {
        // Neither line is above the bound on its own: only their merged quantity is.
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [
                  {"sku": "BEV-COLA-1500", "qty": 999999},
                  {"sku": "BEV-COLA-1500", "qty": 2}]}""");

        assertRejected(result, HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
    }

    /**
     * The seeded gift rules cannot derive a gift above the bound from a valid cart, so this test adds a rule, rolled
     * back with the test: two waters for each 1.5 l cola. 500,000 colas derive exactly the bound, 1,000,000 waters.
     */
    @Test
    void aGiftOfExactlyTheBoundIsQuoted() {
        addTwoWatersPerColaGiftRule();

        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [{"sku": "BEV-COLA-1500", "qty": 500000}]}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.totals.gifts[0].origin").isEqualTo("GIFT:" + TEST_GIFT_RULE);
        assertThat(result).bodyJson().extractingPath("$.totals.gifts[0].qty").isEqualTo(1_000_000);
    }

    /** 500,001 colas, each within the bound, derive 1,000,002 waters: a 400, never a 500 or a wrapped quantity. */
    @Test
    void aGiftQuantityARuleDerivesAboveTheBoundIsInvalid() {
        addTwoWatersPerColaGiftRule();

        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [{"sku": "BEV-COLA-1500", "qty": 500001}]}""");

        assertRejected(result, HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
    }

    @Test
    void aWholeNumberWrittenWithDecimalZerosIsAValidQuantity() {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [{"sku": "BEV-COLA-1500", "qty": 3.00}]}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.lines[0].qty").isEqualTo(3);
    }

    @Test
    void anEmptyCartIsRejected() {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, "{\"paymentMethod\": \"CASH\", \"lines\": []}");

        assertRejected(result, HttpStatus.BAD_REQUEST, "CART_EMPTY");
    }

    @ParameterizedTest(name = "sku [{0}]")
    @ValueSource(strings = {"NO-SUCH-SKU", "SNK-PLANTAIN-45", "bev-cola-1500", ""})
    void aSkuTheTenantDoesNotSellIsUnknown(String sku) {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER,
                "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"" + sku + "\", \"qty\": 1}]}");

        assertRejected(result, HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU");
    }

    @Test
    void oneUnknownSkuRejectsTheWholeCart() {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [
                  {"sku": "BEV-COLA-1500", "qty": 3},
                  {"sku": "SNK-PLANTAIN-45", "qty": 1}]}""");

        assertRejected(result, HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU");
    }

    /**
     * PostgreSQL takes at most 65,535 bind parameters in one statement, so a cart with more distinct SKUs than that is
     * still read in one query and answered with its own code.
     */
    @Test
    void aCartWithMoreDistinctSkusThanOneStatementCanBindIsStillCheckedForUnknownSkus() {
        String unknownLines = IntStream.range(0, 70_000)
                .mapToObj(i -> "{\"sku\": \"NO-SUCH-SKU-" + i + "\", \"qty\": 1}")
                .collect(Collectors.joining(", "));
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, "{\"paymentMethod\": \"CASH\", \"lines\": ["
                + "{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}, " + unknownLines + "]}");

        assertRejected(result, HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBodies")
    void aMalformedBodyIsValidationFailed(String description, String body) {
        assertRejected(post(CO, CO_CASH_CUSTOMER, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    static Stream<Arguments> malformedBodies() {
        String line = "{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}";
        return Stream.of(
                arguments("no paymentMethod", "{\"lines\": [" + line + "]}"),
                arguments("null paymentMethod", "{\"paymentMethod\": null, \"lines\": [" + line + "]}"),
                arguments("unknown paymentMethod", "{\"paymentMethod\": \"BARTER\", \"lines\": [" + line + "]}"),
                arguments("no lines", "{\"paymentMethod\": \"CASH\"}"),
                arguments("null lines", "{\"paymentMethod\": \"CASH\", \"lines\": null}"),
                arguments("null line", "{\"paymentMethod\": \"CASH\", \"lines\": [null]}"),
                arguments("line without sku", "{\"paymentMethod\": \"CASH\", \"lines\": [{\"qty\": 1}]}"),
                arguments("line without qty",
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\"}]}"),
                arguments("qty that is not a number",
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": \"two\"}]}"),
                arguments("qty that is a boolean",
                        "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": true}]}"),
                arguments("not JSON", "{\"paymentMethod\": "),
                arguments("a JSON array", "[" + line + "]"),
                arguments("JSON null", "null"),
                arguments("empty body", ""));
    }

    @Test
    void aMissingTenantHeaderIsValidationFailed() {
        MvcTestResult result = mvc.post().uri(QUOTES)
                .header(QuoteController.CUSTOMER_HEADER, CO_CASH_CUSTOMER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCashCart())
                .exchange();

        assertRejected(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @ParameterizedTest(name = "X-Customer-Id [{0}]")
    @MethodSource("unusableCustomerHeaders")
    void aMissingOrMalformedCustomerHeaderIsValidationFailed(String customerHeader) {
        MockMvcRequestBuilder request = mvc.post().uri(QUOTES)
                .header(QuoteController.TENANT_HEADER, CO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCashCart());
        if (customerHeader != null) {
            request.header(QuoteController.CUSTOMER_HEADER, customerHeader);
        }

        assertRejected(request.exchange(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    static Stream<String> unusableCustomerHeaders() {
        return Stream.of(null, "", "not-a-uuid");
    }

    @Test
    void anUnknownTenantIsRejected() {
        assertRejected(post("zz-unknown", CO_CASH_CUSTOMER, validCashCart()), HttpStatus.BAD_REQUEST,
                "TENANT_NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("customersTheTenantDoesNotHave")
    void aCustomerTheTenantDoesNotHaveIsNotFound(String description, UUID customer) {
        assertRejected(post(CO, customer, validCashCart()), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
    }

    static Stream<Arguments> customersTheTenantDoesNotHave() {
        return Stream.of(
                arguments("unknown customer", UUID.randomUUID()),
                arguments("customer of another tenant", EC_CREDIT_CUSTOMER));
    }

    @Test
    void aCreditQuoteForACustomerWithoutACreditAccountIsRejected() {
        MvcTestResult result = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CREDIT", "lines": [{"sku": "BEV-COLA-1500", "qty": 1}]}""");

        assertRejected(result, HttpStatus.UNPROCESSABLE_CONTENT, "CREDIT_NOT_ENABLED");
    }

    @Test
    void aCreditQuoteOverTheLimitIsStillQuotedBecauseTheCheckIsAdvisory() {
        MvcTestResult result = post(CO, CO_CREDIT_CUSTOMER, """
                {"paymentMethod": "CREDIT", "lines": [{"sku": "BEV-COLA-1500", "qty": 100}]}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.credit.fits").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.credit.shortfall").isEqualTo("118800");
        assertThat(jdbc.sql("SELECT consumed FROM credit_accounts WHERE tenant_id = :tenantId AND customer_id = :id")
                .param("tenantId", CO)
                .param("id", CO_CREDIT_CUSTOMER)
                .query(Long.class)
                .single())
                .as("credit consumed by a quote")
                .isZero();
    }

    @Test
    void aMalformedQuoteIdIsValidationFailed() {
        MvcTestResult result = mvc.get().uri(QUOTES + "/not-a-uuid")
                .header(QuoteController.TENANT_HEADER, CO)
                .header(QuoteController.CUSTOMER_HEADER, CO_CASH_CUSTOMER.toString())
                .exchange();

        assertRejected(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void readingAQuoteUnderAnUnknownTenantIsRejected() {
        MvcTestResult result = mvc.get().uri(QUOTES + "/{quoteId}", UUID.randomUUID())
                .header(QuoteController.TENANT_HEADER, "zz-unknown")
                .header(QuoteController.CUSTOMER_HEADER, CO_CASH_CUSTOMER.toString())
                .exchange();

        assertRejected(result, HttpStatus.BAD_REQUEST, "TENANT_NOT_FOUND");
    }

    /** An active, repeatable co-alpha rule: two waters 600 ml for each 1.5 l cola. */
    private void addTwoWatersPerColaGiftRule() {
        jdbc.sql("""
                        INSERT INTO gift_rules (tenant_id, id, trigger_product_id, trigger_family_code, min_qty,
                                                min_gross, gift_product_id, gift_qty, repeatable, active)
                        VALUES (:tenantId, :id, :cola, NULL, 1, NULL, :water, 2, true, true)""")
                .param("tenantId", CO)
                .param("id", TEST_GIFT_RULE)
                .param("cola", CO_COLA_1500)
                .param("water", CO_WATER_600)
                .update();
    }

    private MvcTestResult post(String tenant, UUID customer, String body) {
        return mvc.post().uri(QUOTES)
                .header(QuoteController.TENANT_HEADER, tenant)
                .header(QuoteController.CUSTOMER_HEADER, customer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private void assertRejected(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
        assertThat(jdbc.sql("SELECT count(*) FROM quotes").query(Long.class).single())
                .as("quotes stored by a rejected request")
                .isZero();
    }

    private static String validCashCart() {
        return "{\"paymentMethod\": \"CASH\", \"lines\": [{\"sku\": \"BEV-COLA-1500\", \"qty\": 1}]}";
    }
}
