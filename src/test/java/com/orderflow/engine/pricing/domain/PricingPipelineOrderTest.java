package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SPEC BR-01 and AC-03: the engine runs its steps in the order of BR-01, on the cart normalized by
 * BR-00. Each lever task adds the cases that pin where its step runs.
 */
class PricingPipelineOrderTest {

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static final UUID COLAS_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000001");
    private static final UUID PLANTAIN_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000000");
    private static final UUID COMBO_RULE = UUID.fromString("01a13cdb-cc00-7002-8004-000000000001");

    private static final List<Product> CATALOG = List.of(
            product("BEV-COLA-350", "colas", "0.60", "0.150000"),
            product("BEV-COLA-1500", "colas", "1.45", "0.150000"),
            product("SNK-PLANTAIN-45", "snacks", "0.55", "0.150000"));

    // The family rule of the ec-alpha seed: units 12 to 23 of the colas get 4%, unit 24 on gets 8%.
    private static final TierRule COLAS = new TierRule(COLAS_RULE, Scope.FAMILY, "colas", List.of(
            new Bracket(1, new BigDecimal("0.000000")),
            new Bracket(12, new BigDecimal("0.040000")),
            new Bracket(24, new BigDecimal("0.080000"))));

    private static final PricingSnapshot EC_ALPHA = new PricingSnapshot(USD, CATALOG, List.of(COLAS), List.of());

    private final PricingEngine engine = new PricingEngine();

    private static Product product(String sku, String family, String listPrice, String taxRate) {
        return new Product(sku, family, usd(listPrice), new BigDecimal(taxRate));
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    private static Discount tier(UUID rule, String amount) {
        return new Discount(Discount.Kind.TIER, rule, usd(amount));
    }

    // 2 colas 1500 and 1 plantain chips, listed at 2.90 + 0.55 = 3.45, with the colas family rule.
    private static PricingSnapshot withCombo(String comboPrice) {
        ComboRule combo = new ComboRule(COMBO_RULE, usd(comboPrice), List.of(
                new Item("BEV-COLA-1500", 2),
                new Item("SNK-PLANTAIN-45", 1)));
        return new PricingSnapshot(USD, CATALOG, List.of(COLAS), List.of(combo));
    }

    private static ComboAllocation part(String amount) {
        return new ComboAllocation(COMBO_RULE, usd(amount));
    }

    @Test
    void normalizesTheCartBeforeTheFirstStep() {
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("SNK-PLANTAIN-45", 1),
                new CartLine("BEV-COLA-1500", 2),
                new CartLine("BEV-COLA-1500", 1))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::gross)
                .containsExactly(
                        tuple("BEV-COLA-1500", 3L, usd("4.35")),
                        tuple("SNK-PLANTAIN-45", 1L, usd("0.55")));
    }

    @Test
    void countsTierThresholdsOnTheMergedLinesOfTheNormalizedCart() {
        // Two client lines of 6 colas are one line of 12: its 12th unit reaches the 4% bracket,
        // 1.45 x 4% = 0.058, rounded to 0.06. Counted per client line, 6 units reach no bracket.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 6),
                new CartLine("SNK-PLANTAIN-45", 1),
                new CartLine("BEV-COLA-1500", 6))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::discounts)
                .containsExactly(
                        tuple("BEV-COLA-1500", 12L, List.of(tier(COLAS_RULE, "0.06"))),
                        tuple("SNK-PLANTAIN-45", 1L, List.of()));
    }

    @Test
    void countsFamilyUnitsInSkuOrderWhateverTheOrderOfTheClientLines() {
        // BEV-COLA-1500 sorts before BEV-COLA-350, so it takes the family units 1 to 8 and
        // BEV-COLA-350 takes 9 to 18 (A-03). Units 12 to 18 are 7 colas 350 at 4%:
        // 7 x 0.60 x 4% = 0.168, rounded to 0.17. In the client's order the colas 1500 would take
        // units 11 to 18 instead: 7 x 1.45 x 4% = 0.406, rounded to 0.41 on the other line.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-350", 10),
                new CartLine("BEV-COLA-1500", 8))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of()),
                        tuple("BEV-COLA-350", List.of(tier(COLAS_RULE, "0.17"))));
    }

    @Test
    void subtractsTheTierDiscountBeforeTheTaxableBaseAndTheTax() {
        // 30 colas: units 12 to 23 give 12 x 1.45 x 4% = 0.696 and units 24 to 30 give
        // 7 x 1.45 x 8% = 0.812, so the line discount is 1.508, rounded once to 1.51. The tax is
        // taken on the net, 43.50 - 1.51 = 41.99: 41.99 x 15% = 6.2985, rounded to 6.30. On the
        // gross it would be 43.50 x 15% = 6.525, rounded to 6.53.
        Breakdown breakdown = engine.price(new Cart(List.of(new CartLine("BEV-COLA-1500", 30))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::gross, LineBreakdown::discounts, LineBreakdown::net,
                        LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(tuple(usd("43.50"), List.of(tier(COLAS_RULE, "1.51")), usd("41.99"),
                        usd("41.99"), usd("6.30")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(usd("43.50"), List.of(tier(COLAS_RULE, "1.51")),
                usd("41.99"), usd("6.30"), usd("48.29")));
    }

    @Test
    void sumsTheRoundedLineDiscountsOfARuleIntoOneTotalsEntryWithoutRoundingAgain() {
        // The colas 1500 take units 1 to 13: units 12 and 13 give 2 x 1.45 x 4% = 0.116, rounded to
        // 0.12. The colas 350 take units 14 to 24: 10 x 0.60 x 4% + 1 x 0.60 x 8% = 0.288, rounded
        // to 0.29. The rule's entry is 0.12 + 0.29 = 0.41; rounding the rule's exact 0.404 would
        // give 0.40. Taxes: 18.73 x 15% = 2.8095 -> 2.81 and 6.31 x 15% = 0.9465 -> 0.95.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 13),
                new CartLine("BEV-COLA-350", 11))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts, LineBreakdown::net, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(tier(COLAS_RULE, "0.12")), usd("18.73"), usd("2.81")),
                        tuple("BEV-COLA-350", List.of(tier(COLAS_RULE, "0.29")), usd("6.31"), usd("0.95")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(usd("25.45"), List.of(tier(COLAS_RULE, "0.41")),
                usd("25.04"), usd("3.76"), usd("28.80")));
    }

    @Test
    void listsTheTotalsDiscountsByOriginWhateverTheOrderOfTheLines() {
        // The plantain rule's id sorts before the colas rule's id, while its line comes after the
        // colas line. 12 colas give 1.45 x 4% = 0.058 -> 0.06; the 2nd plantain gives
        // 0.55 x 10% = 0.055 -> 0.06.
        TierRule plantain = new TierRule(PLANTAIN_RULE, Scope.SKU, "SNK-PLANTAIN-45",
                List.of(new Bracket(2, new BigDecimal("0.100000"))));
        PricingSnapshot snapshot = new PricingSnapshot(USD, CATALOG, List.of(COLAS, plantain), List.of());

        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 12),
                new CartLine("SNK-PLANTAIN-45", 2))), snapshot);

        assertThat(breakdown.lines()).extracting(LineBreakdown::discounts).containsExactly(
                List.of(tier(COLAS_RULE, "0.06")),
                List.of(tier(PLANTAIN_RULE, "0.06")));
        assertThat(breakdown.totals().discounts()).containsExactly(
                tier(PLANTAIN_RULE, "0.06"),
                tier(COLAS_RULE, "0.06"));
    }

    @Test
    void appliesCombosBeforeTiersAndTaxesTheLinesAfterBoth() {
        // Step 2: one application takes 2 of the 14 colas 1500 and the plantain chips, 3.45 for
        // 3.00. Its 0.45 splits into 0.3783 and 0.0717: 37 r 285 and 7 r 60 cents (over 345), and the
        // leftover cent goes to the colas, 0.38 and 0.07. Step 3 counts only the 12 colas left: unit
        // 12 at 4% gives 0.058 -> 0.06. Counted on all 14, as with tiers first, units 12 to 14 would
        // give 0.174 -> 0.17. Step 6 taxes 20.30 - 0.06 - 0.38 = 19.86: 2.979 -> 2.98, where the
        // base before the allocation would give 20.24 x 15% = 3.036 -> 3.04. The chips' 0.48 give
        // 0.072 -> 0.07.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("SNK-PLANTAIN-45", 1),
                new CartLine("BEV-COLA-1500", 14))), withCombo("3.00"));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts, LineBreakdown::comboAllocations,
                        LineBreakdown::net, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(tier(COLAS_RULE, "0.06")), List.of(part("0.38")),
                                usd("19.86"), usd("19.86"), usd("2.98")),
                        tuple("SNK-PLANTAIN-45", List.of(), List.of(part("0.07")),
                                usd("0.48"), usd("0.48"), usd("0.07")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(usd("20.85"),
                List.of(new Discount(Discount.Kind.COMBO, COMBO_RULE, usd("0.45")), tier(COLAS_RULE, "0.06")),
                usd("20.34"), usd("3.05"), usd("23.39")));
    }

    @Test
    void aComboThatSavesNothingLeavesEveryUnitToTheTierStep() {
        // At 3.45 the combo costs what its units list at, so it consumes nothing (BR-03) and the tier
        // step counts all 14 colas 1500: units 12 to 14 give 3 x 1.45 x 4% = 0.174 -> 0.17.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 14),
                new CartLine("SNK-PLANTAIN-45", 1))), withCombo("3.45"));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts, LineBreakdown::comboAllocations)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(tier(COLAS_RULE, "0.17")), List.of()),
                        tuple("SNK-PLANTAIN-45", List.of(), List.of()));
        assertThat(breakdown.totals().discounts()).containsExactly(tier(COLAS_RULE, "0.17"));
    }

    @Test
    void taxesEachLineOnItsTaxableBaseBeforeTheTotalIsSummed() {
        // 4.35 x 0.15 = 0.6525 and 0.55 x 0.15 = 0.0825. Rounded per line (step 6, RP-4) they give
        // 0.65 + 0.08 = 0.73. Taxing the order instead would round 4.90 x 0.15 = 0.735 to 0.74.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 3),
                new CartLine("SNK-PLANTAIN-45", 1))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", usd("4.35"), usd("0.65")),
                        tuple("SNK-PLANTAIN-45", usd("0.55"), usd("0.08")));
        assertThat(breakdown.totals())
                .isEqualTo(new Totals(usd("4.90"), List.of(), usd("4.90"), usd("0.73"), usd("5.63")));
        assertThat(breakdown.currency()).isEqualTo("USD");
    }

    @Test
    void roundsAHalfCentLineTaxWithTheRoundingModeOfTheSnapshotPolicy() {
        // 2 x 0.55 = 1.10 and 1.10 x 0.15 = 0.165, a tie at the USD scale. Step 6 rounds it once
        // with the policy's HALF_UP to 0.17 (RP-4); DOWN, FLOOR, HALF_DOWN and HALF_EVEN give 0.16.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("SNK-PLANTAIN-45", 2))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(tuple("SNK-PLANTAIN-45", usd("1.10"), usd("0.17")));
        assertThat(breakdown.totals())
                .isEqualTo(new Totals(usd("1.10"), List.of(), usd("1.10"), usd("0.17"), usd("1.27")));
    }
}
