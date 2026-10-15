package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins the engine's full breakdown for fixed inputs (SPEC section 11). Each folder under
 * {@code golden/} is one case: {@code request.json} is the body of a quote request, whose lines the
 * engine prices; {@code snapshot.json} is the policy and catalog the engine reads; and
 * {@code expected.json} is the breakdown, with money as strings at the currency scale. A new folder
 * is a new case: every case is compared in full and checked against the BR-05 identities (AC-02).
 */
@Tag("golden")
class GoldenPricingTest {

    // Quantities are longs in the domain, so the fixtures read them as longs too.
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    private static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();

    private final PricingEngine engine = new PricingEngine();

    static Stream<String> cases() throws IOException {
        try (Stream<Path> folders = Files.list(goldenRoot())) {
            return folders.filter(Files::isDirectory)
                    .map(folder -> folder.getFileName().toString())
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void breakdownMatchesTheExpectedOne(String name) {
        GoldenCase golden = GoldenCase.load(name);

        ObjectNode actual = toJson(engine.price(golden.cart(), golden.snapshot()));

        assertThat(actual)
                .as("breakdown of golden case %s:%n%s", name, PRETTY.writeValueAsString(actual))
                .isEqualTo(golden.expected());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void identities(String name) {
        GoldenCase golden = GoldenCase.load(name);
        CurrencyPolicy policy = golden.snapshot().policy();

        Breakdown breakdown = engine.price(golden.cart(), golden.snapshot());

        Totals totals = breakdown.totals();
        Money sumOfNet = sum(breakdown, LineBreakdown::net, policy);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(totals.grossSubtotal()).as("grossSubtotal = sum of line gross")
                    .isEqualTo(sum(breakdown, LineBreakdown::gross, policy));
            softly.assertThat(totals.taxableBase()).as("taxableBase = sum of line net")
                    .isEqualTo(sumOfNet);
            softly.assertThat(totals.taxes()).as("taxes = sum of line tax")
                    .isEqualTo(sum(breakdown, LineBreakdown::tax, policy));
            softly.assertThat(totals.total()).as("total = sum of line net + taxes")
                    .isEqualTo(sumOfNet.plus(totals.taxes()));
            for (LineBreakdown line : breakdown.lines()) {
                softly.assertThat(line.gross()).as("%s: gross = unitPrice x qty", line.sku())
                        .isEqualTo(line.unitPrice().times(line.qty()));
                // net = gross - discount components, and no step of this pipeline emits a component.
                softly.assertThat(line.net()).as("%s: net = gross", line.sku())
                        .isEqualTo(line.gross());
                softly.assertThat(line.taxableBase()).as("%s: taxableBase = net", line.sku())
                        .isEqualTo(line.net());
                BigDecimal exactTax = line.taxableBase().amount().multiply(line.taxRate());
                softly.assertThat(line.tax()).as("%s: tax = round(taxableBase x taxRate)", line.sku())
                        .isEqualTo(new Money(policy.round(exactTax), policy));
            }
        });
    }

    private static Money sum(Breakdown breakdown, Function<LineBreakdown, Money> amount,
            CurrencyPolicy policy) {
        return breakdown.lines().stream().map(amount).reduce(Money.zero(policy), Money::plus);
    }

    private static ObjectNode toJson(Breakdown breakdown) {
        ObjectNode root = JSON.createObjectNode();
        root.put("currency", breakdown.currency());
        ArrayNode lines = root.putArray("lines");
        for (LineBreakdown line : breakdown.lines()) {
            lines.addObject()
                    .put("sku", line.sku())
                    .put("qty", line.qty())
                    .put("unitPrice", money(line.unitPrice()))
                    .put("gross", money(line.gross()))
                    .put("net", money(line.net()))
                    .put("taxableBase", money(line.taxableBase()))
                    .put("taxRate", line.taxRate().toPlainString())
                    .put("tax", money(line.tax()));
        }
        Totals totals = breakdown.totals();
        root.putObject("totals")
                .put("grossSubtotal", money(totals.grossSubtotal()))
                .put("taxableBase", money(totals.taxableBase()))
                .put("taxes", money(totals.taxes()))
                .put("total", money(totals.total()));
        return root;
    }

    private static String money(Money money) {
        return money.amount().toPlainString();
    }

    private static Path goldenRoot() {
        URL root = Objects.requireNonNull(GoldenPricingTest.class.getResource("/golden"),
                "the golden folder is not on the test classpath");
        try {
            return Path.of(root.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private record GoldenCase(Cart cart, PricingSnapshot snapshot, JsonNode expected) {

        static GoldenCase load(String name) {
            Path folder = goldenRoot().resolve(name);
            JsonNode request = JSON.readTree(folder.resolve("request.json"));
            JsonNode snapshot = JSON.readTree(folder.resolve("snapshot.json"));
            JsonNode expected = JSON.readTree(folder.resolve("expected.json"));
            return new GoldenCase(cart(request), snapshot(snapshot), expected);
        }

        private static Cart cart(JsonNode request) {
            List<CartLine> lines = new ArrayList<>();
            for (JsonNode line : request.required("lines")) {
                lines.add(new CartLine(
                        line.required("sku").stringValue(),
                        line.required("qty").longValue()));
            }
            return new Cart(lines);
        }

        private static PricingSnapshot snapshot(JsonNode snapshot) {
            JsonNode policyNode = snapshot.required("policy");
            CurrencyPolicy policy = new CurrencyPolicy(
                    policyNode.required("currency").stringValue(),
                    policyNode.required("scale").intValue(),
                    RoundingMode.valueOf(policyNode.required("roundingMode").stringValue()));
            List<Product> products = new ArrayList<>();
            for (JsonNode product : snapshot.required("products")) {
                products.add(new Product(
                        product.required("sku").stringValue(),
                        new Money(new BigDecimal(product.required("listPrice").stringValue()), policy),
                        new BigDecimal(product.required("taxRate").stringValue())));
            }
            return new PricingSnapshot(policy, products);
        }
    }
}
