package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.LineBreakdown.GiftReference;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.GiftRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.Trigger;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
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
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * {@code golden/} that holds no other folder is one case: {@code request.json} is the body of a
 * quote request, whose lines the engine prices; {@code snapshot.json} is the policy, catalog and
 * active levers the engine reads; and {@code expected.json} is the breakdown, with money as strings
 * at the currency scale. A case that compares variants of one input keeps each variant in a folder
 * of its own inside the case folder, named {@code <case>/<variant>}, and each variant is a case. A
 * new folder is a new case: every case is compared in full and checked against the BR-05 identities
 * (AC-02), and a case folder that misses one of the three files fails. The assignment's golden
 * tests, and the extra goldens of the gift policies, the countries and the rounding edges, also
 * assert their key numbers by name.
 */
@Tag("golden")
class GoldenPricingTest {

    // Quantities are longs in the domain, so the fixtures read them as longs too.
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    private static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();

    private final PricingEngine engine = new PricingEngine();

    // A folder is a case by its place, not by its files, so a case that misses a file fails to load
    // instead of dropping out of the run.
    static Stream<String> cases() throws IOException {
        Path root = goldenRoot();
        List<Path> folders;
        try (Stream<Path> paths = Files.walk(root)) {
            folders = paths.filter(Files::isDirectory).toList();
        }
        return folders.stream()
                .filter(folder -> folders.stream().noneMatch(other -> folder.equals(other.getParent())))
                .map(folder -> caseName(root, folder))
                .sorted();
    }

    // The folder's path below golden/, with forward slashes on every platform.
    private static String caseName(Path root, Path folder) {
        Path relative = root.relativize(folder);
        return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
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
        PricingSnapshot snapshot = golden.snapshot();
        CurrencyPolicy policy = snapshot.policy();

        Breakdown breakdown = engine.price(golden.cart(), snapshot);

        Totals totals = breakdown.totals();
        List<LineBreakdown> paidLines = breakdown.lines().stream().filter(line -> !line.isGift()).toList();
        List<LineBreakdown> giftLines = breakdown.lines().stream().filter(LineBreakdown::isGift).toList();
        Money sumOfNet = sum(breakdown.lines(), LineBreakdown::net, policy);
        Money sumOfLineDiscounts = breakdown.lines().stream()
                .map(line -> sumOfDiscounts(line.discounts(), policy))
                .reduce(Money.zero(policy), Money::plus);
        Money sumOfLineAllocations = breakdown.lines().stream()
                .map(line -> sumOfAllocations(line.comboAllocations(), policy))
                .reduce(Money.zero(policy), Money::plus);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(totals.grossSubtotal()).as("grossSubtotal = sum of paid line gross")
                    .isEqualTo(sum(paidLines, LineBreakdown::gross, policy));
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
            softly.assertThat(totals.taxableBase())
                    .as("taxableBase = sum of paid line net + sum of gift line taxableBase")
                    .isEqualTo(sum(paidLines, LineBreakdown::net, policy)
                            .plus(sum(giftLines, LineBreakdown::taxableBase, policy)));
            softly.assertThat(totals.taxes()).as("taxes = sum of line tax")
                    .isEqualTo(sum(breakdown.lines(), LineBreakdown::tax, policy));
            softly.assertThat(totals.total()).as("total = sum of line net + taxes")
                    .isEqualTo(sumOfNet.plus(totals.taxes()));
            softly.assertThat(totals.gifts()).as("gifts = the gift lines with their quantity and origin")
                    .containsExactlyElementsOf(giftLines.stream()
                            .map(line -> new Gift(line.gift().orElseThrow().ruleId(), line.sku(), line.qty()))
                            .toList());
            for (LineBreakdown line : breakdown.lines()) {
                softly.assertThat(line.gross()).as("%s: gross = unitPrice x qty", line.sku())
                        .isEqualTo(line.unitPrice().times(line.qty()));
                softly.assertThat(line.net())
                        .as("%s: net = gross - sum of line discounts - sum of line comboAllocations", line.sku())
                        .isEqualTo(line.gross().minus(sumOfDiscounts(line.discounts(), policy))
                                .minus(sumOfAllocations(line.comboAllocations(), policy)));
                BigDecimal exactTax = line.taxableBase().amount().multiply(line.taxRate());
                softly.assertThat(line.tax()).as("%s: tax = round(taxableBase x taxRate)", line.sku())
                        .isEqualTo(new Money(policy.round(exactTax), policy));
            }
            for (LineBreakdown line : paidLines) {
                softly.assertThat(line.taxableBase()).as("%s: taxableBase = net", line.sku())
                        .isEqualTo(line.net());
            }
            for (LineBreakdown line : giftLines) {
                Product product = snapshot.product(line.sku()).orElseThrow();
                Money referenceValue = product.listPrice().times(line.qty());
                // BR-04: an exempt gift has no taxable base, and a taxed gift is taxed on its value.
                Money giftBase = snapshot.giftTaxPolicy() == GiftTaxPolicy.TAXED
                        ? referenceValue
                        : Money.zero(policy);
                softly.assertThat(line.unitPrice()).as("%s gift: unitPrice = 0", line.sku())
                        .isEqualTo(Money.zero(policy));
                softly.assertThat(line.discounts()).as("%s gift: no discount", line.sku()).isEmpty();
                softly.assertThat(line.comboAllocations()).as("%s gift: no combo allocation", line.sku())
                        .isEmpty();
                softly.assertThat(line.gift().orElseThrow().referenceValue())
                        .as("%s gift: referenceValue = qty x list price", line.sku())
                        .isEqualTo(referenceValue);
                softly.assertThat(line.taxableBase())
                        .as("%s gift: taxableBase follows the %s policy", line.sku(), snapshot.giftTaxPolicy())
                        .isEqualTo(giftBase);
                softly.assertThat(line.taxRate()).as("%s gift: taxRate = the gift SKU's rate", line.sku())
                        .isEqualTo(product.taxRate());
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
        assertThat(breakdown.totals()).isEqualTo(new Totals(cop(cop, "62500"), List.of(tier), List.of(),
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
        PricingSnapshot withoutCombo = new PricingSnapshot(cop, snapshot.giftTaxPolicy(), List.of(
                snapshot.product("BEV-COLA-350").orElseThrow(),
                snapshot.product("SNK-CHIPS-45").orElseThrow()), snapshot.tierRules(), List.of(), List.of());

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
                List.of(new Discount(Discount.Kind.COMBO, combo, cop(cop, "3600"))), List.of(),
                cop(cop, "10000"), cop(cop, "1529"), cop(cop, "11529")));
    }

    /**
     * GT-03 and AC-06: an order with a gift. The co-alpha rule gives one water 600 for every 10 snacks,
     * repeatable, and Colombia taxes gifts. The cart is 25 chips and 3 waters at 1,500 with 19% tax.
     */
    @Test
    void gt03() {
        GoldenCase golden = GoldenCase.load("gt03-gift");
        PricingSnapshot snapshot = golden.snapshot();
        CurrencyPolicy cop = snapshot.policy();
        UUID giftRule = UUID.fromString("01a13cdb-cc00-7001-8005-000000000001");
        PricingSnapshot withoutGifts = new PricingSnapshot(cop, snapshot.giftTaxPolicy(), List.of(
                snapshot.product("BEV-WATER-600").orElseThrow(),
                snapshot.product("SNK-CHIPS-45").orElseThrow()), snapshot.tierRules(), snapshot.comboRules(),
                List.of());

        Breakdown breakdown = engine.price(golden.cart(), snapshot);
        Breakdown paidOnly = engine.price(golden.cart(), withoutGifts);

        // 25 paid chips hold 2 whole multiples of 10, so the gift line carries 2 waters. It follows the
        // paid lines and never merges with the 3 paid waters. It lists at 2 x 1,500 = 3,000, and
        // Colombia taxes it: 3,000 x 19% = 570.
        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::gift)
                .containsExactly(
                        tuple("BEV-WATER-600", 3L, Optional.empty()),
                        tuple("SNK-CHIPS-45", 25L, Optional.empty()),
                        tuple("BEV-WATER-600", 2L, Optional.of(new GiftReference(giftRule, cop(cop, "3000")))));
        assertThat(breakdown.lines().get(2).gift().orElseThrow().origin())
                .isEqualTo("GIFT:01a13cdb-cc00-7001-8005-000000000001");
        assertThat(breakdown.lines().get(2))
                .extracting(LineBreakdown::unitPrice, LineBreakdown::gross, LineBreakdown::net,
                        LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(cop(cop, "0"), cop(cop, "0"), cop(cop, "0"), cop(cop, "3000"), cop(cop, "570"));
        // The paid lines are what they are without the gift rule: 4,500 with 855 of tax, and 45,000
        // with 2,250, for a total of 49,500 + 3,105 = 52,605.
        assertThat(breakdown.lines().subList(0, 2)).isEqualTo(paidOnly.lines());
        assertThat(paidOnly.totals().total()).isEqualTo(cop(cop, "52605"));
        // With the gift the customer pays the same paid lines, 49,500, and every tax:
        // 855 + 2,250 + 570 = 3,675.
        assertThat(breakdown.totals()).isEqualTo(new Totals(cop(cop, "49500"), List.of(),
                List.of(new Gift(giftRule, "BEV-WATER-600", 2)),
                cop(cop, "52500"), cop(cop, "3675"), cop(cop, "53175")));
    }

    /**
     * AC-12: each gift tax policy follows the table of SPEC BR-04. The two cases price the same cart
     * with the same snapshot but for the country's gift tax policy. The cart is 35 colas 1500 at 1.45
     * USD with 15% tax: their gross of 50.75 reaches the 50.00 of the gift rule, though their net
     * after the colas tier discount does not (A-10). The gift is 2 waters 600 at 0.45.
     */
    @Test
    void giftPolicies() {
        GoldenCase exempt = GoldenCase.load("gift-policy-exempt");
        GoldenCase taxed = GoldenCase.load("gift-policy-taxed");
        CurrencyPolicy usd = exempt.snapshot().policy();
        UUID giftRule = UUID.fromString("01a13cdb-cc00-7002-8005-000000000001");
        Discount tier = new Discount(Discount.Kind.TIER,
                UUID.fromString("01a13cdb-cc00-7002-8003-000000000001"), usd(usd, "2.09"));
        Gift gift = new Gift(giftRule, "BEV-WATER-600", 2);

        Breakdown exemptBreakdown = engine.price(exempt.cart(), exempt.snapshot());
        Breakdown taxedBreakdown = engine.price(taxed.cart(), taxed.snapshot());

        assertThat(GoldenCase.withoutGiftTaxPolicy("gift-policy-exempt"))
                .isEqualTo(GoldenCase.withoutGiftTaxPolicy("gift-policy-taxed"));
        assertThat(exempt.snapshot().giftTaxPolicy()).isEqualTo(GiftTaxPolicy.EXEMPT);
        assertThat(taxed.snapshot().giftTaxPolicy()).isEqualTo(GiftTaxPolicy.TAXED);
        // The paid line is the same under both policies. Units 12 to 23 give 12 x 1.45 x 4% = 0.696
        // and units 24 to 35 give 12 x 1.45 x 8% = 1.392: 2.088, rounded to 2.09. The net is
        // 50.75 - 2.09 = 48.66, below 50.00, and its tax 48.66 x 15% = 7.299 -> 7.30.
        assertThat(exemptBreakdown.lines().get(0)).isEqualTo(taxedBreakdown.lines().get(0));
        assertThat(exemptBreakdown.lines().get(0))
                .extracting(LineBreakdown::discounts, LineBreakdown::net, LineBreakdown::tax)
                .containsExactly(List.of(tier), usd(usd, "48.66"), usd(usd, "7.30"));
        // The gift lists at 2 x 0.45 = 0.90 under both policies. EXEMPT gives it no taxable base and
        // no tax; TAXED taxes the 0.90: 0.135, rounded to 0.14 on the line.
        GiftReference reference = new GiftReference(giftRule, usd(usd, "0.90"));
        assertThat(exemptBreakdown.lines().get(1))
                .extracting(LineBreakdown::gift, LineBreakdown::net, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(Optional.of(reference), usd(usd, "0.00"), usd(usd, "0.00"), usd(usd, "0.00"));
        assertThat(taxedBreakdown.lines().get(1))
                .extracting(LineBreakdown::gift, LineBreakdown::net, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(Optional.of(reference), usd(usd, "0.00"), usd(usd, "0.90"), usd(usd, "0.14"));
        // The taxed gift adds its 0.90 to the taxable base, and only its 0.14 of tax to the total.
        assertThat(exemptBreakdown.totals()).isEqualTo(new Totals(usd(usd, "50.75"), List.of(tier),
                List.of(gift), usd(usd, "48.66"), usd(usd, "7.30"), usd(usd, "55.96")));
        assertThat(taxedBreakdown.totals()).isEqualTo(new Totals(usd(usd, "50.75"), List.of(tier),
                List.of(gift), usd(usd, "49.56"), usd(usd, "7.44"), usd(usd, "56.10")));
    }

    /**
     * AC-11: the same cart priced in CO (COP, scale 0) and in EC (USD, scale 2) follows each country's
     * data, the seeds of co-alpha and ec-alpha. The cart holds only SKUs both tenants sell: 25 colas
     * 350, 6 waters 600 and 24 colas 1.5 l. Nothing in the engine names a country, a currency, a scale
     * or a rate, so every difference below comes from the snapshot.
     */
    @Test
    void multiCountry() {
        GoldenCase co = GoldenCase.load("multi-country-co-vs-ec/co");
        GoldenCase ec = GoldenCase.load("multi-country-co-vs-ec/ec");
        CurrencyPolicy cop = co.snapshot().policy();
        CurrencyPolicy usd = ec.snapshot().policy();
        Discount coTier = new Discount(Discount.Kind.TIER,
                UUID.fromString("01a13cdb-cc00-7001-8003-000000000001"), cop(cop, "2750"));
        UUID ecTierRule = UUID.fromString("01a13cdb-cc00-7002-8003-000000000001");
        UUID ecGiftRule = UUID.fromString("01a13cdb-cc00-7002-8005-000000000001");

        Breakdown coBreakdown = engine.price(co.cart(), co.snapshot());
        Breakdown ecBreakdown = engine.price(ec.cart(), ec.snapshot());

        assertThat(co.cart()).isEqualTo(ec.cart());
        // RF-5: the JDK lists COP with 2 decimals, yet every CO amount has the scale 0 of the country
        // data, and every EC amount the scale 2 of USD.
        assertThat(Currency.getInstance("COP").getDefaultFractionDigits()).isEqualTo(2);
        assertThat(coBreakdown.currency()).isEqualTo("COP");
        assertThat(ecBreakdown.currency()).isEqualTo("USD");
        assertThat(amounts(coBreakdown)).allSatisfy(amount -> assertThat(amount.amount().scale()).isZero());
        assertThat(amounts(ecBreakdown)).allSatisfy(amount -> assertThat(amount.amount().scale()).isEqualTo(2));
        // CO: the tier rule targets the cola 350 alone, so the colas 1.5 l neither count nor earn a
        // discount. Units 10 to 19 give 10 x 2,500 x 5% = 1,250 and units 20 to 25 give
        // 6 x 2,500 x 10% = 1,500: 2,750, as in GT-01. Every tax is 19%: 124,800 x 19% = 23,712,
        // 59,750 x 19% = 11,352.5 -> 11,353 (half a peso, HALF_UP) and 9,000 x 19% = 1,710. The gift
        // rule needs 10 snacks, so no gift.
        assertThat(coBreakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::gross, LineBreakdown::discounts,
                        LineBreakdown::net, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", cop(cop, "124800"), List.of(), cop(cop, "124800"), cop(cop, "23712")),
                        tuple("BEV-COLA-350", cop(cop, "62500"), List.of(coTier), cop(cop, "59750"), cop(cop, "11353")),
                        tuple("BEV-WATER-600", cop(cop, "9000"), List.of(), cop(cop, "9000"), cop(cop, "1710")));
        assertThat(coBreakdown.totals()).isEqualTo(new Totals(cop(cop, "196300"), List.of(coTier), List.of(),
                cop(cop, "193550"), cop(cop, "36775"), cop(cop, "230325")));
        // EC: the tier rule targets the colas family, counted in SKU order (A-03). The colas 1.5 l take
        // units 1 to 24: 12 x 1.45 x 4% + 1 x 1.45 x 8% = 0.812 -> 0.81. The colas 350 take units 25
        // to 49, all at 8%: 25 x 0.60 x 8% = 1.20. Every tax is 15%: 33.99 x 15% = 5.0985 -> 5.10,
        // 13.80 x 15% = 2.07 and 2.70 x 15% = 0.405 -> 0.41 (half a cent, HALF_UP). The gross,
        // 34.80 + 15.00 + 2.70 = 52.50, reaches the 50.00 of the gift rule: 2 waters that list at
        // 0.90, with no taxable base, because Ecuador exempts gifts.
        assertThat(ecBreakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::gross, LineBreakdown::discounts,
                        LineBreakdown::net, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", usd(usd, "34.80"),
                                List.of(new Discount(Discount.Kind.TIER, ecTierRule, usd(usd, "0.81"))),
                                usd(usd, "33.99"), usd(usd, "33.99"), usd(usd, "5.10")),
                        tuple("BEV-COLA-350", usd(usd, "15.00"),
                                List.of(new Discount(Discount.Kind.TIER, ecTierRule, usd(usd, "1.20"))),
                                usd(usd, "13.80"), usd(usd, "13.80"), usd(usd, "2.07")),
                        tuple("BEV-WATER-600", usd(usd, "2.70"), List.of(), usd(usd, "2.70"), usd(usd, "2.70"),
                                usd(usd, "0.41")),
                        tuple("BEV-WATER-600", usd(usd, "0.00"), List.of(), usd(usd, "0.00"), usd(usd, "0.00"),
                                usd(usd, "0.00")));
        assertThat(ecBreakdown.lines().get(3).gift())
                .contains(new GiftReference(ecGiftRule, usd(usd, "0.90")));
        // The family's entry is its two rounded line discounts, 0.81 + 1.20 = 2.01. The total is the
        // net 50.49 plus the taxes 5.10 + 2.07 + 0.41 = 7.58.
        assertThat(ecBreakdown.totals()).isEqualTo(new Totals(usd(usd, "52.50"),
                List.of(new Discount(Discount.Kind.TIER, ecTierRule, usd(usd, "2.01"))),
                List.of(new Gift(ecGiftRule, "BEV-WATER-600", 2)),
                usd(usd, "50.49"), usd(usd, "7.58"), usd(usd, "58.07")));
    }

    /**
     * The rounding edges of SPEC section 4 on the co-alpha catalog, COP at scale 0 with HALF_UP: a tier
     * discount, two combo allocations and a tax that each fall on half a peso. The cart is 15 colas
     * 350, 12 waters 600, a cola 1.5 l and 2 chips. Besides the seeded rule on the cola 350, a rule
     * takes 2.5% off every water from the 6th on, and a combo sells a cola 1.5 l with 2 chips for 7,799.
     */
    @Test
    void roundingHalfCases() {
        GoldenCase golden = GoldenCase.load("rounding-half-cases");
        CurrencyPolicy cop = golden.snapshot().policy();
        UUID combo = UUID.fromString("01a13cdb-cc00-7001-8004-000000000002");
        Discount colaTier = new Discount(Discount.Kind.TIER,
                UUID.fromString("01a13cdb-cc00-7001-8003-000000000001"), cop(cop, "750"));
        Discount waterTier = new Discount(Discount.Kind.TIER,
                UUID.fromString("01a13cdb-cc00-7001-8003-000000000002"), cop(cop, "263"));

        Breakdown breakdown = engine.price(golden.cart(), golden.snapshot());

        // RP-2, combo: 5,200 + 3,600 = 8,800 sold for 7,799 is 1,001 off. The exact shares
        // 1,001 x 5,200 / 8,800 = 591.5 and 1,001 x 3,600 / 8,800 = 409.5 truncate to 591 and 409; the
        // leftover peso goes to the first SKU of the tie, BEV-COLA-1500: 592 and 409.
        // RP-2, tier: units 6 to 12 of the waters, 7 x 1,500 x 2.5% = 262.5 -> 263. The cola 350's
        // 6 x 2,500 x 5% = 750 needs no rounding.
        // RP-4: 4,608 x 19% = 875.52 -> 876; 36,750 x 19% = 6,982.5 -> 6,983; 17,737 x 19% = 3,370.03
        // -> 3,370; 3,191 x 5% = 159.55 -> 160.
        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts, LineBreakdown::comboAllocations,
                        LineBreakdown::net, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(), List.of(new ComboAllocation(combo, cop(cop, "592"))),
                                cop(cop, "4608"), cop(cop, "876")),
                        tuple("BEV-COLA-350", List.of(colaTier), List.of(), cop(cop, "36750"), cop(cop, "6983")),
                        tuple("BEV-WATER-600", List.of(waterTier), List.of(), cop(cop, "17737"), cop(cop, "3370")),
                        tuple("SNK-CHIPS-45", List.of(), List.of(new ComboAllocation(combo, cop(cop, "409"))),
                                cop(cop, "3191"), cop(cop, "160")));
        // RP-5: the taxes are the sum of the line taxes, 876 + 6,983 + 3,370 + 160 = 11,389. Rounding
        // the exact sum 875.52 + 6,982.5 + 3,370.03 + 159.55 = 11,387.60 would give 11,388.
        assertThat(breakdown.totals()).isEqualTo(new Totals(cop(cop, "64300"),
                List.of(new Discount(Discount.Kind.COMBO, combo, cop(cop, "1001")), colaTier, waterTier),
                List.of(), cop(cop, "62286"), cop(cop, "11389"), cop(cop, "73675")));
    }

    private static Money cop(CurrencyPolicy cop, String amount) {
        return new Money(new BigDecimal(amount), cop);
    }

    private static Money usd(CurrencyPolicy usd, String amount) {
        return new Money(new BigDecimal(amount), usd);
    }

    private static Money sum(List<LineBreakdown> lines, Function<LineBreakdown, Money> amount,
            CurrencyPolicy policy) {
        return lines.stream().map(amount).reduce(Money.zero(policy), Money::plus);
    }

    private static Money sumOfDiscounts(List<Discount> discounts, CurrencyPolicy policy) {
        return discounts.stream().map(Discount::amount).reduce(Money.zero(policy), Money::plus);
    }

    private static Money sumOfAllocations(List<ComboAllocation> allocations, CurrencyPolicy policy) {
        return allocations.stream().map(ComboAllocation::amount).reduce(Money.zero(policy), Money::plus);
    }

    // Every amount of a breakdown: each line's and each total's money, discounts and gifts included.
    private static List<Money> amounts(Breakdown breakdown) {
        List<Money> amounts = new ArrayList<>();
        for (LineBreakdown line : breakdown.lines()) {
            amounts.addAll(List.of(line.unitPrice(), line.gross(), line.net(), line.taxableBase(), line.tax()));
            line.discounts().forEach(discount -> amounts.add(discount.amount()));
            line.comboAllocations().forEach(allocation -> amounts.add(allocation.amount()));
            line.gift().ifPresent(gift -> amounts.add(gift.referenceValue()));
        }
        Totals totals = breakdown.totals();
        amounts.addAll(List.of(totals.grossSubtotal(), totals.taxableBase(), totals.taxes(), totals.total()));
        totals.discounts().forEach(discount -> amounts.add(discount.amount()));
        return amounts;
    }

    private static ObjectNode toJson(Breakdown breakdown) {
        ObjectNode root = JSON.createObjectNode();
        root.put("currency", breakdown.currency());
        ArrayNode lines = root.putArray("lines");
        for (LineBreakdown line : breakdown.lines()) {
            ObjectNode node = lines.addObject()
                    .put("sku", line.sku())
                    .put("qty", line.qty())
                    .put("isGift", line.isGift());
            line.gift().ifPresent(gift -> node.put("origin", gift.origin()));
            node.put("unitPrice", money(line.unitPrice()))
                    .put("gross", money(line.gross()));
            putDiscounts(node, line.discounts());
            ArrayNode allocations = node.putArray("comboAllocations");
            for (ComboAllocation allocation : line.comboAllocations()) {
                allocations.addObject()
                        .put("comboId", allocation.comboId().toString())
                        .put("amount", money(allocation.amount()));
            }
            node.put("net", money(line.net()));
            line.gift().ifPresent(gift -> node.put("referenceValue", money(gift.referenceValue())));
            node.put("taxableBase", money(line.taxableBase()))
                    .put("taxRate", line.taxRate().toPlainString())
                    .put("tax", money(line.tax()));
        }
        Totals totals = breakdown.totals();
        ObjectNode totalsNode = root.putObject("totals")
                .put("grossSubtotal", money(totals.grossSubtotal()));
        putDiscounts(totalsNode, totals.discounts());
        ArrayNode gifts = totalsNode.putArray("gifts");
        for (Gift gift : totals.gifts()) {
            gifts.addObject()
                    .put("sku", gift.sku())
                    .put("qty", gift.qty())
                    .put("origin", gift.origin());
        }
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

        /** The case's request and snapshot as written, but for the gift tax policy. */
        static JsonNode withoutGiftTaxPolicy(String name) {
            Path folder = goldenRoot().resolve(name);
            ObjectNode inputs = JSON.createObjectNode();
            inputs.set("request", JSON.readTree(folder.resolve("request.json")));
            ObjectNode snapshot = (ObjectNode) JSON.readTree(folder.resolve("snapshot.json"));
            ((ObjectNode) snapshot.required("policy")).remove("giftTaxPolicy");
            inputs.set("snapshot", snapshot);
            return inputs;
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
            return new PricingSnapshot(policy,
                    GiftTaxPolicy.valueOf(policyNode.required("giftTaxPolicy").stringValue()),
                    products,
                    tierRules(snapshot.required("tierRules")),
                    comboRules(snapshot.required("comboRules"), policy),
                    giftRules(snapshot.required("giftRules"), policy));
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

        private static List<GiftRule> giftRules(JsonNode rules, CurrencyPolicy policy) {
            List<GiftRule> giftRules = new ArrayList<>();
            for (JsonNode rule : rules) {
                giftRules.add(new GiftRule(
                        UUID.fromString(rule.required("id").stringValue()),
                        trigger(rule.required("trigger"), policy),
                        rule.required("giftSku").stringValue(),
                        rule.required("giftQty").longValue(),
                        rule.required("repeatable").booleanValue()));
            }
            return giftRules;
        }

        // A trigger names a SKU or a family with a minimum quantity, or a minimum gross alone, like a
        // row of the gift_rules table.
        private static Trigger trigger(JsonNode trigger, CurrencyPolicy policy) {
            if (trigger.has("sku")) {
                return new SkuQuantity(trigger.required("sku").stringValue(), trigger.required("minQty").longValue());
            }
            if (trigger.has("family")) {
                return new FamilyQuantity(
                        trigger.required("family").stringValue(), trigger.required("minQty").longValue());
            }
            return new GrossSubtotal(new Money(new BigDecimal(trigger.required("minGross").stringValue()), policy));
        }
    }
}
