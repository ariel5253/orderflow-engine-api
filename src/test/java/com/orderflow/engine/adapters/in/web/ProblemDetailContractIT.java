package com.orderflow.engine.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** AC-18: every error is RFC 9457 problem+json with a stable code and the request's correlation id. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ProblemProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailContractIT {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String PROBLEMS = "https://orderflow-engine.example/problems/";

    /**
     * The catalog of SPEC section 8 with the type URIs of api-contract section 3, typed out by hand and
     * never derived from {@link ErrorCode}, so that a code served with another status or type fails.
     */
    private static final List<SpecRow> SPEC_CATALOG = List.of(
            new SpecRow("VALIDATION_FAILED", 400, PROBLEMS + "validation-failed"),
            new SpecRow("INVALID_QUANTITY", 400, PROBLEMS + "invalid-quantity"),
            new SpecRow("CART_EMPTY", 400, PROBLEMS + "cart-empty"),
            new SpecRow("TENANT_NOT_FOUND", 400, PROBLEMS + "tenant-not-found"),
            new SpecRow("IDEMPOTENCY_KEY_MISSING", 400, PROBLEMS + "idempotency-key-missing"),
            new SpecRow("IDEMPOTENCY_KEY_INVALID", 400, PROBLEMS + "idempotency-key-invalid"),
            new SpecRow("QUOTE_NOT_FOUND", 404, PROBLEMS + "quote-not-found"),
            new SpecRow("ORDER_NOT_FOUND", 404, PROBLEMS + "order-not-found"),
            new SpecRow("CUSTOMER_NOT_FOUND", 404, PROBLEMS + "customer-not-found"),
            new SpecRow("REQUEST_NOT_SUPPORTED", 404, PROBLEMS + "request-not-supported"),
            new SpecRow("QUOTE_EXPIRED", 409, PROBLEMS + "quote-expired"),
            new SpecRow("QUOTE_ALREADY_CONFIRMED", 409, PROBLEMS + "quote-already-confirmed"),
            new SpecRow("CREDIT_LIMIT_EXCEEDED", 409, PROBLEMS + "credit-limit-exceeded"),
            new SpecRow("IDEMPOTENCY_KEY_IN_PROGRESS", 409, PROBLEMS + "idempotency-key-in-progress"),
            new SpecRow("UNKNOWN_SKU", 422, PROBLEMS + "unknown-sku"),
            new SpecRow("CREDIT_NOT_ENABLED", 422, PROBLEMS + "credit-not-enabled"),
            new SpecRow("IDEMPOTENCY_KEY_REUSED", 422, PROBLEMS + "idempotency-key-reused"),
            new SpecRow("INTERNAL_ERROR", 500, PROBLEMS + "internal-error"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeIsAProblemWithItsStatusTypeAndCode(ErrorCode code) {
        MvcTestResult result = mvc.get().uri(ProblemProbeController.BASE_PATH + "/{code}", code).exchange();

        assertThat(result).hasStatus(code.status()).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(code.type().toString());
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(code.status().value());
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code.name());
        assertThat(result).bodyJson().extractingPath("$.title").asString().isNotBlank().isNotEqualTo(code.titleKey());
        assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank().isNotEqualTo(code.detailKey());
        assertThat(result).bodyJson().extractingPath("$.correlationId")
                .isEqualTo(result.getResponse().getHeader(CORRELATION_HEADER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specCatalog")
    void everyCodeAnswersTheStatusAndTypeTheSpecGivesIt(SpecRow row) {
        MvcTestResult result = mvc.get().uri(ProblemProbeController.BASE_PATH + "/{code}", row.code()).exchange();

        assertThat(result).hasStatus(row.status()).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(row.status());
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(row.type());
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(row.code());
    }

    @Test
    void theCatalogHoldsExactlyTheSpecCodes() {
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name))
                .as("ErrorCode against the SPEC section 8 catalog")
                .containsExactlyInAnyOrderElementsOf(SPEC_CATALOG.stream().map(SpecRow::code).toList());
    }

    @Test
    void aMalformedBodyIsValidationFailedWithoutParserDetails() throws Exception {
        MvcTestResult result = mvc.post().uri(ProblemProbeController.BODY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": ")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ErrorCode.VALIDATION_FAILED.type().toString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("JSON", "parse", "end-of-input");
    }

    @Test
    void aMethodTheRouteDoesNotSupportKeeps405AndAllowAndIsRequestNotSupported() {
        MvcTestResult result = mvc.delete().uri(ProblemProbeController.UNEXPECTED_PATH).exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("REQUEST_NOT_SUPPORTED");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(PROBLEMS + "request-not-supported");
    }

    @Test
    void anUnknownRouteKeeps404AndIsRequestNotSupported() {
        MvcTestResult result = mvc.get().uri("/api/v1/no-such-route").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("REQUEST_NOT_SUPPORTED");
    }

    @Test
    void anUnsupportedMediaTypeKeeps415AndIsRequestNotSupported() {
        MvcTestResult result = mvc.post().uri(ProblemProbeController.BODY_PATH)
                .contentType(MediaType.TEXT_PLAIN)
                .content("value")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("REQUEST_NOT_SUPPORTED");
    }

    @Test
    void anUnexpectedErrorIsInternalErrorAndHidesItsInternals() throws Exception {
        MvcTestResult result = mvc.get().uri(ProblemProbeController.UNEXPECTED_PATH).exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(ProblemProbeController.INTERNAL_DETAIL, "IllegalStateException", "trace", "exception");
    }

    @Test
    void anUnexpectedErrorIsLoggedWithTheCorrelationIdOfItsBody(CapturedOutput output) {
        MvcTestResult result = mvc.get().uri(ProblemProbeController.UNEXPECTED_PATH)
                .header(CORRELATION_HEADER, "probe-500-correlation")
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo("probe-500-correlation");
        assertThat(output.getAll()).containsPattern("\\[probe-500-correlation\\].*Unexpected error")
                .contains(ProblemProbeController.INTERNAL_DETAIL);
    }

    @Test
    void aValidCorrelationIdIsEchoed() {
        MvcTestResult result = mvc.get().uri(ProblemProbeController.BASE_PATH + "/QUOTE_EXPIRED")
                .header(CORRELATION_HEADER, "client-trace_01.A")
                .exchange();

        assertThat(result).headers().hasValue(CORRELATION_HEADER, "client-trace_01.A");
        assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo("client-trace_01.A");
    }

    @ParameterizedTest
    @MethodSource("unusableCorrelationIds")
    void aMissingOrUnsafeCorrelationIdIsReplacedByAGeneratedOne(String supplied) {
        MockMvcRequestBuilder request = mvc.get().uri(ProblemProbeController.BASE_PATH + "/QUOTE_EXPIRED");
        if (supplied != null) {
            request.header(CORRELATION_HEADER, supplied);
        }

        MvcTestResult result = request.exchange();

        String generated = result.getResponse().getHeader(CORRELATION_HEADER);
        assertThat(generated).as("generated correlation id").matches(UUID);
        assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo(generated);
    }

    @Test
    void aSuccessfulResponseCarriesTheCorrelationIdToo() {
        MvcTestResult result = mvc.post().uri(ProblemProbeController.BODY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": \"ok\"}")
                .header(CORRELATION_HEADER, "client-trace-ok")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).headers().hasValue(CORRELATION_HEADER, "client-trace-ok");
    }

    static Stream<String> unusableCorrelationIds() {
        return Stream.of(null, "", "has spaces", "semi;colon", "line\tbreak", "x".repeat(129));
    }

    static List<SpecRow> specCatalog() {
        return SPEC_CATALOG;
    }

    record SpecRow(String code, int status, String type) {
    }
}
