package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * engine prices; {@code snapshot.json} is the policy, catalog and active levers the engine reads;
 * and {@code expected.json} is the breakdown, with money as strings at the currency scale. A new
 * folder is a new case: every case is compared in full and checked against the BR-05 identities
 * (AC-02). The assignment's golden tests also assert their key numbers by name.
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
        Money sumOfLineDiscounts = breakdown.lines().stream()
                .map(line -> sumOfDiscounts(line.discounts(), policy))
                .reduce(Money.zero(policy), Money::plus);
        Money sumOfLineAllocations = breakdown.lines().stream()
                .map(line -> sumOfAllocations(line.comboAllocations(), policy))
                .reduce(Money.zero(policy), Money::plus);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(totals.grossSubtotal()).as("grossSubtotal = sum of line gross")
                    .isEqualTo(sum(breakdown, LineBreakdown::gross, policy));
            softly.assertThat(sumOfDiscounts(totals.discounts(), policy))
                    .as("sum of discounts[].amount = sum of line tier discounts + sum of line comboAllocations")
                    .isEqualTo(sumOfLineDiscounts.plus(sumOfLineAllocations));
            for (Discount combo : totals.discounts().stream()
                    .filter(discount -> discount.kind() == Discount.Kind.COMBO).toList()) {
                softly.assertThat(breakdown.lines().stream()
                                .flatMap(line -> line.comboAllocations().stream())
                                .filter(part -> part.comboId().equals(combo.ruleId()))
                                .map(ComboAllocation::amount)
                                .reduce(Money.zero(policy), Money::plus))
                        .as("%s = sum of its line allocations", combo.origin())
                        .isEqualTo(combo.amount());
            }
            softly.assertThat(totals.taxableBase()).as("taxableBase = sum of line net")
                    .isEqualTo(sumOfNet);
            softly.assertThat(totals.taxes()).as("taxes = sum of line tax")
                    .isEqualTo(sum(breakdown, LineBreakdown::tax, policy));
            softly.assertThat(totals.total()).as("total = sum of line net + taxes")
                    .isEqualTo(sumOfNet.plus(totals.taxes()));
            for (LineBreakdown line : breakdown.lines()) {
                softly.assertThat(line.gross()).as("%s: gross = unitPrice x qty", line.sku())
                        .isEqualTo(line.unitPrice().times(line.qty()));
                softly.assertThat(line.net())
                        .as("%s: net = gross - sum of line discounts - sum of line comboAllocations", line.sku())
                        .isEqualTo(line.gross().minus(sumOfDiscounts(line.discounts(), policy))
                                .minus(sumOfAllocations(line.comboAllocations(), policy)));
                softly.assertThat(line.taxableBase()).as("%s: taxableBase = net", line.sku())
                        .isEqualTo(line.net());
                BigDecimal exactTax = line.taxableBase().amount().multiply(line.taxRate());
                softly.assertThat(line.tax()).as("%s: tax = round(taxableBase x taxRate)", line.sku())
                        .isEqualTo(new Money(policy.round(exactTax), policy));
            }
        });
    }

    /**
     * GT-01 and AC-04, with the worked numbers of SPEC BR-02: 25 units of BEV-COLA-350 at 2,500
     * COP, brackets 1-9 at 0%, 10-19 at 5% and 20+ at 10%.
     */
    @Test
    void gt01() {
        GoldenCase golden = GoldenCase.load("gt01-two-tier-brackets");
        CurrencyPolicy cop = golden.snapshot().policy();
        // Marginal brackets: 10 units x 2,500 x 5% = 1,250 and 6 units x 2,500 x 10% = 1,500.
        Money discount = cop(cop, "1250").plus(cop(cop, "1500"));
        Discount tier = new Discount(Discount.Kind.TIER,
                UUID.fromString("01a13cdb-cc00-7001-8003-000000000001"), discount);

        Breakdown breakdown = engine.price(golden.cart(), golden.snapshot());

        assertThat(discount).isEqualTo(cop(cop, "2750"));
        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::gross, LineBreakdown::discounts,
                        LineBreakdown::net, LineBreakdown::tax)
                // Net 62,500 - 2,750 = 59,750, and 59,750 x 19% = 11,352.50, rounded HALF_UP to 11,353.
                .containsExactly(tuple("BEV-COLA-350", cop(cop, "62500"), List.of(tier),
                        cop(cop, "59750"), cop(cop, "11353")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(cop(cop, "62500"), List.of(tier),
                cop(cop, "59750"), cop(cop, "11353"), cop(cop, "71103")));
    }

    /**
     * GT-02 and AC-05: the products of a combo never show a loose discount. The cart is 21 colas 350
     * and 10 chips. The snapshot holds the combo of 2 colas and 1 chips at 5,000, and the tier rule
     * of the cola 350, 10-19 at 5% and 20+ at 10%, which must not fire.
     */
    @Test
    void gt02() {
        GoldenCase golden = GoldenCase.load("gt02-combo-no-loose-discount");
        PricingSnapshot snapshot = golden.snapshot();
        CurrencyPolicy cop = snapshot.policy();
        UUID combo = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");
        UUID tierRule = UUID.fromString("01a13cdb-cc00-7001-8003-000000000001");
        PricingSnapshot withoutCombo = new PricingSnapshot(cop, List.of(
                snapshot.product("BEV-COLA-350").orElseThrow(),
                snapshot.product("SNK-CHIPS-45").orElseThrow()), snapshot.tierRules(), List.of());

        Breakdown breakdown = engine.price(golden.cart(), snapshot);
        Breakdown tiersOnly = engine.price(golden.cart(), withoutCombo);

        // 10 applications consume 20 colas (50,000) and 10 chips (18,000) for 10 x 5,000: a discount
        // of 18,000. Its exact shares 13,235.29 and 4,764.71 truncate to 17,999, and the leftover
        // peso goes to the larger remainder: 13,235 and 4,765. The one cola left is unit 1 of the
        // tier count, below the 5% bracket.
        assertThat(breakdown.totals().discounts())
                .containsExactly(new Discount(Discount.Kind.COMBO, combo, cop(cop, "18000")));
        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts, LineBreakdown::comboAllocations)
                .containsExactly(
                        tuple("BEV-COLA-350", List.of(), List.of(new ComboAllocation(combo, cop(cop, "13235")))),
                        tuple("SNK-CHIPS-45", List.of(), List.of(new ComboAllocation(combo, cop(cop, "4765")))));
        // Without the combo the same 21 colas earn the tier rule: 10 x 2,500 x 5% + 2 x 2,500 x 10%
        // = 1,750, so the assertions above can fail.
        assertThat(tiersOnly.totals().discounts())
                .containsExactly(new Discount(Discount.Kind.TIER, tierRule, cop(cop, "1750")));
    }

    /**
     * GT-04 and AC-07, with the worked numbers of SPEC BR-03: 4 colas 350 at 2,500 with 19% tax and
     * 2 chips at 1,800 with 5% tax, and the combo of 2 colas and 1 chips at 5,000.
     */
    @Test
    void gt04() {
        GoldenCase golden = GoldenCase.load("gt04-combo-with-taxes");
        CurrencyPolicy cop = golden.snapshot().policy();
        UUID combo = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");

        Breakdown breakdown = engine.price(golden.cart(), golden.snapshot());

        // 2 applications list 10,000 + 3,600 = 13,600 for 10,000: a discount of 3,600, allocated
        // 2,647 and 953. The tax is taken on the allocated bases: 7,353 x 19% = 1,397.07 -> 1,397
        // and 2,647 x 5% = 132.35 -> 132. Total 10,000 + 1,529 = 11,529.
        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::gross, LineBreakdown::comboAllocations,
                        LineBreakdown::net, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-350", cop(cop, "10000"), List.of(new ComboAllocation(combo, cop(cop, "2647"))),
                                cop(cop, "7353"), cop(cop, "7353"), cop(cop, "1397")),
                        tuple("SNK-CHIPS-45", cop(cop, "3600"), List.of(new ComboAllocation(combo, cop(cop, "953"))),
                                cop(cop, "2647"), cop(cop, "2647"), cop(cop, "132")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(cop(cop, "13600"),
                List.of(new Discount(Discount.Kind.COMBO, combo, cop(cop, "3600"))),
                cop(cop, "10000"), cop(cop, "1529"), cop(cop, "11529")));
    }

    private static Money cop(CurrencyPolicy cop, String amount) {
        return new Money(new BigDecimal(amount), cop);
    }

    private static Money sum(Breakdown breakdown, Function<LineBreakdown, Money> amount,
            CurrencyPolicy policy) {
        return breakdown.lines().stream().map(amount).reduce(Money.zero(policy), Money::plus);
    }

    private static Money sumOfDiscounts(List<Discount> discounts, CurrencyPolicy policy) {
        return discounts.stream().map(Discount::amount).reduce(Money.zero(policy), Money::plus);
    }

    private static Money sumOfAllocations(List<ComboAllocation> allocations, CurrencyPolicy policy) {
        return allocations.stream().map(ComboAllocation::amount).reduce(Money.zero(policy), Money::plus);
    }

    private static ObjectNode toJson(Breakdown breakdown) {
        ObjectNode root = JSON.createObjectNode();
        root.put("currency", breakdown.currency());
        ArrayNode lines = root.putArray("lines");
        for (LineBreakdown line : breakdown.lines()) {
            ObjectNode node = lines.addObject()
                    .put("sku", line.sku())
                    .put("qty", line.qty())
                    .put("unitPrice", money(line.unitPrice()))
                    .put("gross", money(line.gross()));
            putDiscounts(node, line.discounts());
            ArrayNode allocations = node.putArray("comboAllocations");
            for (ComboAllocation allocation : line.comboAllocations()) {
                allocations.addObject()
                        .put("comboId", allocation.comboId().toString())
                        .put("amount", money(allocation.amount()));
            }
            node.put("net", money(line.net()))
                    .put("taxableBase", money(line.taxableBase()))
                    .put("taxRate", line.taxRate().toPlainString())
                    .put("tax", money(line.tax()));
        }
        Totals totals = breakdown.totals();
        ObjectNode totalsNode = root.putObject("totals")
                .put("grossSubtotal", money(totals.grossSubtotal()));
        putDiscounts(totalsNode, totals.discounts());
        totalsNode.put("taxableBase", money(totals.taxableBase()))
                .put("taxes", money(totals.taxes()))
                .put("total", money(totals.total()));
        return root;
    }

    private static void putDiscounts(ObjectNode node, List<Discount> discounts) {
        ArrayNode array = node.putArray("discounts");
        for (Discount discount : discounts) {
            array.addObject()
                    .put("origin", discount.origin())
                    .put("amount", money(discount.amount()));
        }
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
                        product.required("family").stringValue(),
                        new Money(new BigDecimal(product.required("listPrice").stringValue()), policy),
                        new BigDecimal(product.required("taxRate").stringValue())));
            }
            return new PricingSnapshot(policy, products, tierRules(snapshot.required("tierRules")),
                    comboRules(snapshot.required("comboRules"), policy));
        }

        private static List<TierRule> tierRules(JsonNode rules) {
            List<TierRule> tierRules = new ArrayList<>();
            for (JsonNode rule : rules) {
                List<Bracket> brackets = new ArrayList<>();
                for (JsonNode bracket : rule.required("brackets")) {
                    brackets.add(new Bracket(
                            bracket.required("from").longValue(),
                            new BigDecimal(bracket.required("percent").stringValue())));
                }
                tierRules.add(new TierRule(
                        UUID.fromString(rule.required("id").stringValue()),
                        Scope.valueOf(rule.required("scope").stringValue()),
                        rule.required("target").stringValue(),
                        brackets));
            }
            return tierRules;
        }

        private static List<ComboRule> comboRules(JsonNode rules, CurrencyPolicy policy) {
            List<ComboRule> comboRules = new ArrayList<>();
            for (JsonNode rule : rules) {
                List<Item> items = new ArrayList<>();
                for (JsonNode item : rule.required("items")) {
                    items.add(new Item(item.required("sku").stringValue(), item.required("minQty").longValue()));
                }
                comboRules.add(new ComboRule(
                        UUID.fromString(rule.required("id").stringValue()),
                        new Money(new BigDecimal(rule.required("comboPrice").stringValue()), policy),
                        items));
            }
            return comboRules;
        }
    }
}
