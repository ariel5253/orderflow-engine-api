package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CASH_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-53 and api-contract E-07: the product list gives the caller tenant's products only, in SKU order, each with its
 * SKU, its name as the description, its list price as a string at the currency scale and its tax rate as a string,
 * with the currency once per body and no lever, family, product id or credit data. Its headers are checked as E-01
 * checks them, and no answer carries a CORS header, because the web client reaches the API through its own origin
 * (architecture section 1.1). The expected values are those of the seed changelogs: the seeds, and the renaming of
 * their names into the tenants' language. Every test runs in a transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ProductContractIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PRODUCTS = "/api/v1/products";

    /** The order of the E-07 body fields in the contract. */
    private static final List<String> BODY_FIELDS = List.of("currency", "items");

    /** The order of the fields of an E-07 item in the contract: no item repeats the currency. */
    private static final List<String> ITEM_FIELDS = List.of("sku", "description", "listPrice", "taxRate");

    /**
     * The four co-alpha products of the seeds, in SKU order: "BEV-COLA-1500" sorts before "BEV-COLA-350" because "1"
     * comes before "3", although the seeds insert the 350 ml cola first. COP has scale 0.
     */
    private static final String CO_PRODUCTS = """
            {"currency": "COP",
             "items": [
               {"sku": "BEV-COLA-1500", "description": "Gaseosa cola 1,5 l", "listPrice": "5200",
                "taxRate": "0.190000"},
               {"sku": "BEV-COLA-350", "description": "Gaseosa cola 350 ml", "listPrice": "2500",
                "taxRate": "0.190000"},
               {"sku": "BEV-WATER-600", "description": "Agua 600 ml", "listPrice": "1500", "taxRate": "0.190000"},
               {"sku": "SNK-CHIPS-45", "description": "Papas fritas 45 g", "listPrice": "1800", "taxRate": "0.050000"}
             ]}""";

    /**
     * The four ec-alpha products of the seeds, in SKU order. USD has scale 2, so 0.60 keeps its trailing zero. The
     * colas and the water share their SKUs and names with co-alpha, but not their prices or rates of tax.
     */
    private static final String EC_PRODUCTS = """
            {"currency": "USD",
             "items": [
               {"sku": "BEV-COLA-1500", "description": "Gaseosa cola 1,5 l", "listPrice": "1.45",
                "taxRate": "0.150000"},
               {"sku": "BEV-COLA-350", "description": "Gaseosa cola 350 ml", "listPrice": "0.60",
                "taxRate": "0.150000"},
               {"sku": "BEV-WATER-600", "description": "Agua 600 ml", "listPrice": "0.45", "taxRate": "0.150000"},
               {"sku": "SNK-PLANTAIN-45", "description": "Chifles 45 g", "listPrice": "0.55", "taxRate": "0.150000"}
             ]}""";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void coAlphaListsItsProductsInSkuOrderWithPesosAtScaleZero() {
        MvcTestResult result = list(CO, CO_CREDIT_CUSTOMER.toString());

        assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        JsonNode body = body(result);
        assertThat(body.propertyNames()).containsExactlyElementsOf(BODY_FIELDS);
        body.required("items").forEach(item -> assertThat(item.propertyNames()).containsExactlyElementsOf(ITEM_FIELDS));
        assertThat(body).isEqualTo(JSON.readTree(CO_PRODUCTS));
    }

    @Test
    void ecAlphaListsDollarsAtScaleTwoAndNeverAProductOfCoAlpha() {
        MvcTestResult result = list(EC, EC_CASH_CUSTOMER.toString());

        assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        JsonNode body = body(result);
        assertThat(body.propertyNames()).containsExactlyElementsOf(BODY_FIELDS);
        assertThat(body).isEqualTo(JSON.readTree(EC_PRODUCTS));
        assertThat(skus(body)).as("SKUs of ec-alpha").doesNotContain("SNK-CHIPS-45");
    }

    @Test
    void everyCustomerOfTheTenantReadsTheSameList() {
        assertThat(body(list(CO, CO_CASH_CUSTOMER.toString()))).isEqualTo(JSON.readTree(CO_PRODUCTS));
    }

    @Test
    void aMissingTenantHeaderIsAValidationFailure() {
        MvcTestResult result = mvc.get().uri(PRODUCTS)
                .header(QuoteController.CUSTOMER_HEADER, CO_CREDIT_CUSTOMER.toString())
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void aMissingCustomerHeaderIsAValidationFailure() {
        MvcTestResult result = mvc.get().uri(PRODUCTS)
                .header(QuoteController.TENANT_HEADER, CO)
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void aCustomerIdThatIsNotAUuidIsAValidationFailure() {
        assertProblem(list(CO, "not-a-uuid"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void anUnknownTenantIsRejected() {
        assertProblem(list("zz-unknown", CO_CREDIT_CUSTOMER.toString()), HttpStatus.BAD_REQUEST, "TENANT_NOT_FOUND");
    }

    @Test
    void anUnknownCustomerIsNotFound() {
        assertProblem(list(CO, UUID.randomUUID().toString()), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void aCustomerOfAnotherTenantIsNotFound() {
        assertProblem(list(CO, EC_CASH_CUSTOMER.toString()), HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void aRequestFromAForeignOriginGetsNoCorsHeader() {
        MvcTestResult result = mvc.get().uri(PRODUCTS)
                .header(QuoteController.TENANT_HEADER, CO)
                .header(QuoteController.CUSTOMER_HEADER, CO_CREDIT_CUSTOMER.toString())
                .header(HttpHeaders.ORIGIN, "https://shop.example.org")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).headers().doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private MvcTestResult list(String tenant, String customer) {
        return mvc.get().uri(PRODUCTS)
                .header(QuoteController.TENANT_HEADER, tenant)
                .header(QuoteController.CUSTOMER_HEADER, customer)
                .exchange();
    }

    private static List<String> skus(JsonNode body) {
        return StreamSupport.stream(body.required("items").spliterator(), false)
                .map(item -> item.required("sku").stringValue())
                .toList();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = body(result);
        assertThat(problem.required("status").intValue()).isEqualTo(status.value());
        assertThat(problem.required("code").stringValue()).isEqualTo(code);
        assertThat(problem.required("type").stringValue())
                .isEqualTo("https://orderflow-engine.example/problems/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        assertThat(problem.required("instance").stringValue()).isEqualTo(PRODUCTS);
        assertThat(problem.required("title").stringValue()).isNotBlank();
        assertThat(problem.required("detail").stringValue()).isNotBlank();
        assertThat(problem.required("correlationId").stringValue()).isNotBlank();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
