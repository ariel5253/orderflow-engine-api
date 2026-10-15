package com.orderflow.engine.quoting;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.adapters.in.web.QuoteResponse;
import com.orderflow.engine.pricing.domain.Cart;
import com.orderflow.engine.pricing.domain.CartLine;
import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingEngine;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden tests of the quote use case (SPEC section 11). Credit is evaluated outside the pure engine, so these cases run
 * the whole use case over ports backed by the case's fixtures, with a fixed clock and a fixed quote id.
 * {@code request.json} is the quote request; {@code snapshot.json} holds the country policy, the quote TTL, the
 * catalog and the customer's credit account; {@code expected-quote.json} is the quote body the API returns. The same
 * folder also holds {@code expected.json}, the engine breakdown that {@code GoldenPricingTest} pins together with the
 * BR-05 identities.
 */
@Tag("golden")
class GoldenQuoteTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    private static final String TENANT = "golden-tenant";
    private static final UUID CUSTOMER = UUID.fromString("01a13cdb-cc00-7001-8001-000000000005");
    private static final UUID QUOTE_ID = UUID.fromString("01a13cdb-cc00-7001-8006-000000000005");
    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    /**
     * GT-05, AC-17 (quote side): a CREDIT quote whose total exceeds the available credit reports {@code fits=false},
     * the available credit and the shortfall. The total is 442,680 and the customer has 500,000 - 100,000 = 400,000
     * available, so it falls 42,680 short.
     */
    @Test
    void gt05() {
        GoldenQuote golden = GoldenQuote.load("gt05-credit-exceeded");

        QuoteView quote = golden.useCase().create(TENANT, CUSTOMER, golden.paymentMethod(), golden.cart());

        CurrencyPolicy cop = golden.policy().currency();
        assertThat(quote.credit()).isEqualTo(new CreditAdvice(false,
                new Money(new BigDecimal("400000"), cop), new Money(new BigDecimal("42680"), cop)));
        JsonNode actual = JSON.valueToTree(QuoteResponse.from(quote));
        assertThat(actual)
                .as("quote of golden case gt05-credit-exceeded:%n%s", JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(actual))
                .isEqualTo(golden.expected());
    }

    private static Path goldenRoot() {
        URL root = Objects.requireNonNull(GoldenQuoteTest.class.getResource("/golden"),
                "the golden folder is not on the test classpath");
        try {
            return Path.of(root.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private record GoldenQuote(PaymentMethod paymentMethod, Cart cart, TenantPolicy policy,
            PricingSnapshot pricing, Map<String, String> descriptions, Money creditLimit, Money creditConsumed,
            JsonNode expected) {

        static GoldenQuote load(String name) {
            Path folder = goldenRoot().resolve(name);
            JsonNode request = JSON.readTree(folder.resolve("request.json"));
            JsonNode snapshot = JSON.readTree(folder.resolve("snapshot.json"));

            List<CartLine> lines = new ArrayList<>();
            for (JsonNode line : request.required("lines")) {
                lines.add(new CartLine(line.required("sku").stringValue(), line.required("qty").longValue()));
            }

            JsonNode policyNode = snapshot.required("policy");
            CurrencyPolicy currency = new CurrencyPolicy(
                    policyNode.required("currency").stringValue(),
                    policyNode.required("scale").intValue(),
                    RoundingMode.valueOf(policyNode.required("roundingMode").stringValue()));
            List<Product> products = new ArrayList<>();
            Map<String, String> descriptions = new HashMap<>();
            for (JsonNode product : snapshot.required("products")) {
                String sku = product.required("sku").stringValue();
                products.add(new Product(sku, product.required("family").stringValue(),
                        money(product.required("listPrice"), currency),
                        new BigDecimal(product.required("taxRate").stringValue())));
                descriptions.put(sku, product.required("name").stringValue());
            }
            JsonNode credit = snapshot.required("credit");

            return new GoldenQuote(
                    PaymentMethod.valueOf(request.required("paymentMethod").stringValue()),
                    new Cart(lines),
                    new TenantPolicy(currency, Duration.parse(snapshot.required("quoteTtl").stringValue())),
                    new PricingSnapshot(currency, products, List.of()),
                    descriptions,
                    money(credit.required("limit"), currency),
                    money(credit.required("consumed"), currency),
                    JSON.readTree(folder.resolve("expected-quote.json")));
        }

        private static Money money(JsonNode amount, CurrencyPolicy currency) {
            return new Money(new BigDecimal(amount.stringValue()), currency);
        }

        CreateQuoteUseCase useCase() {
            return new CreateQuoteUseCase(new FixtureSnapshots(), new FixtureCredit(), new InMemoryQuotes(),
                    new PricingEngine(), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        /** The fixture's policy, customer and catalog, whatever tenant and customer are asked for. */
        private final class FixtureSnapshots implements SnapshotProvider {

            @Override
            public Optional<TenantPolicy> tenantPolicy(String tenantId) {
                return Optional.of(policy);
            }

            @Override
            public boolean customerExists(String tenantId, UUID customerId) {
                return true;
            }

            @Override
            public PricingSnapshot pricingSnapshot(String tenantId, CurrencyPolicy currency, Set<String> skus) {
                return pricing;
            }
        }

        /** The fixture's credit account, checked by the same rule as the database-backed advisor. */
        private final class FixtureCredit implements CreditAdvisor {

            @Override
            public Optional<CreditAdvice> advise(String tenantId, UUID customerId, Money total) {
                return Optional.of(CreditAdvice.of(total, creditLimit, creditConsumed));
            }
        }

        /** Keeps the one quote the case seals, under the fixed id, with the fixture's product names. */
        private final class InMemoryQuotes implements QuoteRepository {

            private final Map<UUID, SealedQuote> stored = new HashMap<>();

            @Override
            public UUID insert(SealedQuote quote) {
                stored.put(QUOTE_ID, quote);
                return QUOTE_ID;
            }

            @Override
            public Optional<StoredQuote> find(String tenantId, UUID customerId, UUID quoteId) {
                return Optional.ofNullable(stored.get(quoteId))
                        .map(quote -> new StoredQuote(quoteId, quote, descriptions, false));
            }
        }
    }
}
