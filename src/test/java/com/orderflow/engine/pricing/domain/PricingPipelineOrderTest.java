package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.LineBreakdown.GiftReference;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.GiftRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
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
    private static final UUID GIFT_RULE = UUID.fromString("01a13cdb-cc00-7002-8005-000000000001");

    private static final List<Product> CATALOG = List.of(
            product("BEV-COLA-350", "colas", "0.60", "0.150000"),
            product("BEV-COLA-1500", "colas", "1.45", "0.150000"),
            product("SNK-PLANTAIN-45", "snacks", "0.55", "0.150000"),
            product("BEV-WATER-600", "waters", "0.45", "0.150000"));

    // The family rule of the ec-alpha seed: units 12 to 23 of the colas get 4%, unit 24 on gets 8%.
    private static final TierRule COLAS = new TierRule(COLAS_RULE, Scope.FAMILY, "colas", List.of(
            new Bracket(1, new BigDecimal("0.000000")),
            new Bracket(12, new BigDecimal("0.040000")),
            new Bracket(24, new BigDecimal("0.080000"))));

    private static final PricingSnapshot EC_ALPHA =
            new PricingSnapshot(USD, GiftTaxPolicy.EXEMPT, CATALOG, List.of(COLAS), List.of(), List.of());

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

    // 2 colas 1500 and 1 plantain chips, listed at 2.90 + 0.55 = 3.45.
    private static ComboRule combo(String comboPrice) {
        return new ComboRule(COMBO_RULE, usd(comboPrice), List.of(
                new Item("BEV-COLA-1500", 2),
                new Item("SNK-PLANTAIN-45", 1)));
    }

    // The combo, with the colas family rule.
    private static PricingSnapshot withCombo(String comboPrice) {
        return new PricingSnapshot(USD, GiftTaxPolicy.EXEMPT, CATALOG, List.of(COLAS), List.of(combo(comboPrice)),
                List.of());
    }

    private static ComboAllocation part(String amount) {
        return new ComboAllocation(COMBO_RULE, usd(amount));
    }

    // One gift rule that applies once at most, with the colas family rule and the given combos.
    private static PricingSnapshot withGift(GiftTaxPolicy giftTaxPolicy, List<ComboRule> combos,
            GiftRule.Trigger trigger, String giftSku, long giftQty) {
        GiftRule gift = new GiftRule(GIFT_RULE, trigger, giftSku, giftQty, false);
        return new PricingSnapshot(USD, giftTaxPolicy, CATALOG, List.of(COLAS), combos, List.of(gift));
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
                List.of(), usd("41.99"), usd("6.30"), usd("48.29")));
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
                List.of(), usd("25.04"), usd("3.76"), usd("28.80")));
    }

    @Test
    void listsTheTotalsDiscountsByOriginWhateverTheOrderOfTheLines() {
        // The plantain rule's id sorts before the colas rule's id, while its line comes after the
        // colas line. 12 colas give 1.45 x 4% = 0.058 -> 0.06; the 2nd plantain gives
        // 0.55 x 10% = 0.055 -> 0.06.
        TierRule plantain = new TierRule(PLANTAIN_RULE, Scope.SKU, "SNK-PLANTAIN-45",
                List.of(new Bracket(2, new BigDecimal("0.100000"))));
        PricingSnapshot snapshot = new PricingSnapshot(USD, GiftTaxPolicy.EXEMPT, CATALOG, List.of(COLAS, plantain),
                List.of(), List.of());

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
                List.of(), usd("20.34"), usd("3.05"), usd("23.39")));
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
                .isEqualTo(new Totals(usd("4.90"), List.of(), List.of(), usd("4.90"), usd("0.73"), usd("5.63")));
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
                .isEqualTo(new Totals(usd("1.10"), List.of(), List.of(), usd("1.10"), usd("0.17"), usd("1.27")));
    }

    @Test
    void evaluatesGiftsOnThePaidUnitsBeforeAComboConsumesThem() {
        // Step 1 counts the 3 paid colas 1500, so the rule "3 colas 1500 give a water" applies before
        // the combo of step 2 consumes 2 of them with the plantain chips, 3.45 for 3.00, allocated 0.38
        // and 0.07. Counted after the combo, the 1 cola left would not reach the 3 of the rule.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 3),
                new CartLine("SNK-PLANTAIN-45", 1))), withGift(GiftTaxPolicy.EXEMPT, List.of(combo("3.00")),
                new SkuQuantity("BEV-COLA-1500", 3), "BEV-WATER-600", 1));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::isGift,
                        LineBreakdown::comboAllocations)
                .containsExactly(
                        tuple("BEV-COLA-1500", 3L, false, List.of(part("0.38"))),
                        tuple("SNK-PLANTAIN-45", 1L, false, List.of(part("0.07"))),
                        tuple("BEV-WATER-600", 1L, true, List.of()));
        assertThat(breakdown.totals().gifts()).containsExactly(new Gift(GIFT_RULE, "BEV-WATER-600", 1));
    }

    @Test
    void keepsAGiftOfAPaidSkuOnItsOwnLineAndOutOfTheTierCount() {
        // 11 paid colas 350 earn one more cola 350. The gift is a line of its own after the paid line,
        // and the tier step does not count its unit (A-04), so the 11 paid units stay below the 4%
        // bracket. Merged with the paid line or counted, unit 12 would earn 0.60 x 4% = 0.024 -> 0.02.
        Breakdown breakdown = engine.price(new Cart(List.of(new CartLine("BEV-COLA-350", 11))),
                withGift(GiftTaxPolicy.EXEMPT, List.of(), new SkuQuantity("BEV-COLA-350", 11), "BEV-COLA-350", 1));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::isGift, LineBreakdown::gross,
                        LineBreakdown::discounts)
                .containsExactly(
                        tuple("BEV-COLA-350", 11L, false, usd("6.60"), List.of()),
                        tuple("BEV-COLA-350", 1L, true, usd("0.00"), List.of()));
        assertThat(breakdown.totals().discounts()).isEmpty();
    }

    @Test
    void keepsGiftUnitsOutOfTheCombos() {
        // The plantain chips earn one cola 1500. With the paid cola it would fill the combo of 2 colas
        // 1500 and 1 plantain chips, but a combo consumes paid units only, so the combo does not apply.
        // The gift line follows the paid lines, though its SKU sorts first.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 1),
                new CartLine("SNK-PLANTAIN-45", 1))), withGift(GiftTaxPolicy.EXEMPT, List.of(combo("3.00")),
                new SkuQuantity("SNK-PLANTAIN-45", 1), "BEV-COLA-1500", 1));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::isGift,
                        LineBreakdown::comboAllocations)
                .containsExactly(
                        tuple("BEV-COLA-1500", 1L, false, List.of()),
                        tuple("SNK-PLANTAIN-45", 1L, false, List.of()),
                        tuple("BEV-COLA-1500", 1L, true, List.of()));
        assertThat(breakdown.totals().discounts()).isEmpty();
    }

    @Test
    void evaluatesAGrossSubtotalGiftOnTheGrossBeforeDiscounts() {
        // 14 colas 1500 list at 20.30, which reaches the 20.30 of the rule. Step 3 then takes
        // 3 x 1.45 x 4% = 0.174 -> 0.17 off the line: on the net of 20.13 the gift would not apply
        // (A-10).
        Breakdown breakdown = engine.price(new Cart(List.of(new CartLine("BEV-COLA-1500", 14))),
                withGift(GiftTaxPolicy.EXEMPT, List.of(), new GrossSubtotal(usd("20.30")), "BEV-WATER-600", 1));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::isGift, LineBreakdown::discounts, LineBreakdown::net)
                .containsExactly(
                        tuple("BEV-COLA-1500", false, List.of(tier(COLAS_RULE, "0.17")), usd("20.13")),
                        tuple("BEV-WATER-600", true, List.of(), usd("0.00")));
    }

    @Test
    void taxesATaxedGiftOnItsOwnLineAndAddsOnlyItsTaxToTheTotal() {
        // The plantain chips earn 3 waters 600, listed at 1.35. Under the TAXED policy step 5 makes the
        // 1.35 the gift line's taxable base, and step 6 rounds its tax on its own: 0.2025 -> 0.20, next
        // to 0.55 x 15% = 0.0825 -> 0.08 for the paid line. Taxed together, 1.90 x 15% = 0.285 would
        // round to 0.29. Step 7 adds the taxes to the net, which only the paid line has:
        // 0.55 + 0.28 = 0.83.
        Breakdown breakdown = engine.price(new Cart(List.of(new CartLine("SNK-PLANTAIN-45", 1))),
                withGift(GiftTaxPolicy.TAXED, List.of(), new SkuQuantity("SNK-PLANTAIN-45", 1), "BEV-WATER-600", 3));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::gift, LineBreakdown::net, LineBreakdown::taxableBase,
                        LineBreakdown::tax)
                .containsExactly(
                        tuple("SNK-PLANTAIN-45", Optional.empty(), usd("0.55"), usd("0.55"), usd("0.08")),
                        tuple("BEV-WATER-600", Optional.of(new GiftReference(GIFT_RULE, usd("1.35"))), usd("0.00"),
                                usd("1.35"), usd("0.20")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(usd("0.55"), List.of(),
                List.of(new Gift(GIFT_RULE, "BEV-WATER-600", 3)), usd("1.90"), usd("0.28"), usd("0.83")));
    }
}
