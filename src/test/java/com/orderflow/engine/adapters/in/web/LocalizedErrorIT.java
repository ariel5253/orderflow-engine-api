package com.orderflow.engine.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-19: the error text follows Accept-Language. A missing, malformed or unsupported value falls
 * back to app.i18n.default-locale, which is es unless configured otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ProblemProbeController.class)
class LocalizedErrorIT {

    private static final String PROBE = ProblemProbeController.BASE_PATH + "/" + ErrorCode.CREDIT_LIMIT_EXCEEDED;
    private static final Text SPANISH = new Text("Cupo de cr\u00e9dito insuficiente",
            "El total del pedido supera el cupo disponible.");
    private static final Text ENGLISH = new Text("Credit limit exceeded",
            "The order total exceeds the available credit.");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest(name = "Accept-Language [{0}]")
    @MethodSource("acceptLanguages")
    void theTextFollowsAcceptLanguageAndFallsBackToSpanish(String acceptLanguage, Text expected) {
        assertText(mvc, acceptLanguage, expected);
    }

    static Stream<Arguments> acceptLanguages() {
        return Stream.of(
                arguments(null, SPANISH),
                arguments("", SPANISH),
                arguments("es", SPANISH),
                arguments("es-CO", SPANISH),
                arguments("en", ENGLISH),
                arguments("en-US,en;q=0.9", ENGLISH),
                arguments("en;q=0.2, es;q=0.9", SPANISH),
                arguments("fr", SPANISH),
                arguments("fr-FR, en;q=0.5", ENGLISH),
                arguments("*", SPANISH),
                arguments("en;q=high", SPANISH));
    }

    @Nested
    @TestPropertySource(properties = "app.i18n.default-locale=en")
    class WithEnglishAsTheConfiguredDefault {

        @Autowired
        private MockMvcTester englishDefaultMvc;

        @ParameterizedTest(name = "Accept-Language [{0}]")
        @NullSource
        @ValueSource(strings = {"fr", "en;q=high"})
        void aMissingOrUnsupportedLanguageFallsBackToTheConfiguredDefault(String acceptLanguage) {
            assertText(englishDefaultMvc, acceptLanguage, ENGLISH);
        }

        @Test
        void aSupportedLanguageStillWins() {
            assertText(englishDefaultMvc, "es", SPANISH);
        }
    }

    private static void assertText(MockMvcTester client, String acceptLanguage, Text expected) {
        MockMvcRequestBuilder request = client.get().uri(PROBE);
        if (acceptLanguage != null) {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }

        MvcTestResult result = request.exchange();

        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED.name());
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo(expected.title());
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(expected.detail());
    }

    record Text(String title, String detail) {
    }
}
