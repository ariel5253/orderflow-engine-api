package com.orderflow.engine.pricing.domain;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * AC-09 and SPEC section 4: an amount is rounded only per line, at RP-2 (each discount component)
 * and RP-4 (each tax), to the country's scale with the country's rounding mode. The gross (RP-1) and
 * the net (RP-3) are exact, and every total is a sum of rounded line values that is never rounded
 * again (RP-5). The cases price the co-alpha catalog in COP at scale 0, so each tie is half a peso,
 * under every rounding mode a country may choose: the {@code countries} table allows every
 * {@link RoundingMode} but {@code UNNECESSARY}.
 */
class RoundingPolicyTest {

    private static final UUID COLA_RULE = UUID.fromString("01a13cdb-cc00-7001-8003-000000000001");
    private static final UUID WATER_RULE = UUID.fromString("01a13cdb-cc00-7001-8003-000000000002");
    private static final UUID COMBO_RULE = UUID.fromString("01a13cdb-cc00-7001-8004-000000000002");

    // A BigDecimal method or constructor that rounds: one that takes a RoundingMode or a MathContext,
    // or a legacy overload of setScale or divide that takes the rounding mode as an int.
    private static final DescribedPredicate<JavaAccess<?>> ROUNDS_A_BIG_DECIMAL = describe(
            "a BigDecimal method or constructor that rounds",
            access -> access.getTarget() instanceof CodeUnitAccessTarget target
                    && target.getOwner().isEquivalentTo(BigDecimal.class)
                    && takesARoundingSetting(target));

    // CurrencyPolicy.round, reached from anywhere but the tier step (RP-2) and Money.timesRounded (RP-4).
    private static final DescribedPredicate<JavaAccess<?>> ROUNDS_OUTSIDE_RP2_AND_RP4 = describe(
            "CurrencyPolicy.round outside the tier step and Money.timesRounded",
            access -> reaches(access, CurrencyPolicy.class, "round")
                    && !access.getOriginOwner().isEquivalentTo(TierDiscountStep.class)
                    && !(access.getOriginOwner().isEquivalentTo(Money.class)
                            && access.getOrigin().getName().equals("timesRounded")));

    private static final DescribedPredicate<JavaAccess<?>> MULTIPLIES_BY_A_RATE_AND_ROUNDS = describe(
            "Money.timesRounded",
            access -> reaches(access, Money.class, "timesRounded"));

    private final PricingEngine engine = new PricingEngine();

    // The co-alpha catalog and its tier rule on the cola 350, a rule that takes 2.5% off every water
    // from the 6th on, and a combo that sells a cola 1.5 l with 2 chips for 7,799, all in COP with the
    // given mode.
    private static PricingSnapshot coAlpha(RoundingMode mode) {
        CurrencyPolicy cop = new CurrencyPolicy("COP", 0, mode);
        List<Product> catalog = List.of(
                new Product("BEV-COLA-350", "colas", cop(cop, "2500"), new BigDecimal("0.190000")),
                new Product("BEV-COLA-1500", "colas", cop(cop, "5200"), new BigDecimal("0.190000")),
                new Product("SNK-CHIPS-45", "snacks", cop(cop, "1800"), new BigDecimal("0.050000")),
                new Product("BEV-WATER-600", "waters", cop(cop, "1500"), new BigDecimal("0.190000")));
        TierRule colas = new TierRule(COLA_RULE, Scope.SKU, "BEV-COLA-350", List.of(
                new Bracket(1, new BigDecimal("0.000000")),
                new Bracket(10, new BigDecimal("0.050000")),
                new Bracket(20, new BigDecimal("0.100000"))));
        TierRule waters = new TierRule(WATER_RULE, Scope.SKU, "BEV-WATER-600", List.of(
                new Bracket(1, new BigDecimal("0.000000")),
                new Bracket(6, new BigDecimal("0.025000"))));
        ComboRule combo = new ComboRule(COMBO_RULE, cop(cop, "7799"), List.of(
                new Item("BEV-COLA-1500", 1),
                new Item("SNK-CHIPS-45", 2)));
        return new PricingSnapshot(cop, GiftTaxPolicy.TAXED, catalog, List.of(colas, waters), List.of(combo),
                List.of());
    }

    private static Money cop(CurrencyPolicy cop, String amount) {
        return new Money(new BigDecimal(amount), cop);
    }

    private static Cart cart(CartLine... lines) {
        return new Cart(List.of(lines));
    }

    @ParameterizedTest
    @CsvSource({
            "HALF_UP, 263, 17737",
            "HALF_DOWN, 262, 17738",
            "HALF_EVEN, 262, 17738",
            "UP, 263, 17737",
            "DOWN, 262, 17738",
            "CEILING, 263, 17737",
            "FLOOR, 262, 17738"})
    void roundsATierDiscountOnceForItsLineWithTheCountryMode(RoundingMode mode, String discount, String net) {
        // RP-2: 12 waters reach units 6 to 12 of the 2.5% bracket, 7 x 1,500 x 2.5% = 262.5, half a peso,
        // which the country's mode rounds once for the line. RP-1: the gross, 12 x 1,500 = 18,000, is the
        // same under every mode. RP-3: the net is 18,000 minus the rounded discount, with nothing left
        // to round.
        PricingSnapshot snapshot = coAlpha(mode);
        CurrencyPolicy cop = snapshot.policy();

        Breakdown breakdown = engine.price(cart(new CartLine("BEV-WATER-600", 12)), snapshot);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::gross, LineBreakdown::discounts, LineBreakdown::net)
                .containsExactly(tuple(cop(cop, "18000"),
                        List.of(new Discount(Discount.Kind.TIER, WATER_RULE, cop(cop, discount))), cop(cop, net)));
    }

    @ParameterizedTest
    @EnumSource(value = RoundingMode.class, names = "UNNECESSARY", mode = EnumSource.Mode.EXCLUDE)
    void splitsAComboDiscountByLargestRemainderWhateverTheCountryMode(RoundingMode mode) {
        // RP-2 for a combo: a cola 1.5 l and 2 chips list at 5,200 + 3,600 = 8,800 and sell for 7,799, a
        // discount of 1,001. The exact shares 1,001 x 5,200 / 8,800 = 591.5 and 1,001 x 3,600 / 8,800
        // = 409.5 are both half a peso. Largest remainder truncates them to 591 and 409 and gives the
        // leftover peso to the first SKU of the tie, BEV-COLA-1500, under every mode. Rounding each share
        // with the mode would give 592 + 410 = 1,002 (HALF_UP, HALF_EVEN, UP, CEILING) or 591 + 409
        // = 1,000 (HALF_DOWN, DOWN, FLOOR) instead of the 1,001 of the combo.
        PricingSnapshot snapshot = coAlpha(mode);
        CurrencyPolicy cop = snapshot.policy();

        Breakdown breakdown = engine.price(cart(
                new CartLine("SNK-CHIPS-45", 2),
                new CartLine("BEV-COLA-1500", 1)), snapshot);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::comboAllocations, LineBreakdown::net)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(new ComboAllocation(COMBO_RULE, cop(cop, "592"))),
                                cop(cop, "4608")),
                        tuple("SNK-CHIPS-45", List.of(new ComboAllocation(COMBO_RULE, cop(cop, "409"))),
                                cop(cop, "3191")));
        assertThat(breakdown.totals().discounts())
                .containsExactly(new Discount(Discount.Kind.COMBO, COMBO_RULE, cop(cop, "1001")));
    }

    @ParameterizedTest
    @CsvSource({
            "HALF_UP, 6983",
            "HALF_DOWN, 6982",
            "HALF_EVEN, 6982",
            "UP, 6983",
            "DOWN, 6982",
            "CEILING, 6983",
            "FLOOR, 6982"})
    void roundsEachLineTaxOnceWithTheCountryMode(RoundingMode mode, String tax) {
        // 15 colas 350 reach units 10 to 15 of the 5% bracket: 6 x 2,500 x 5% = 750, exact, so no mode
        // changes it. RP-4: the net, 37,500 - 750 = 36,750, is taxed at 19%: 6,982.5, half a peso, which
        // the country's mode rounds once for the line.
        PricingSnapshot snapshot = coAlpha(mode);
        CurrencyPolicy cop = snapshot.policy();

        Breakdown breakdown = engine.price(cart(new CartLine("BEV-COLA-350", 15)), snapshot);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::discounts, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(tuple(List.of(new Discount(Discount.Kind.TIER, COLA_RULE, cop(cop, "750"))),
                        cop(cop, "36750"), cop(cop, tax)));
    }

    @Test
    void sumsRoundedLineValuesIntoTotalsThatAreNeverRoundedAgain() {
        // RP-4 with HALF_UP, the mode of every seeded country: 36,750 x 19% = 6,982.5 -> 6,983 for the
        // colas 350, and for the combo's lines 4,608 x 19% = 875.52 -> 876 and 3,191 x 5% = 159.55
        // -> 160. RP-5: the taxes are the sum of the line taxes, 8,019. Rounding the order's exact tax,
        // 6,982.5 + 875.52 + 159.55 = 8,017.57, would give 8,018, and so would rounding once per tax
        // rate: (36,750 + 4,608) x 19% = 7,858.02 -> 7,858, plus 160. The total is the net,
        // 36,750 + 4,608 + 3,191 = 44,549, plus the taxes.
        PricingSnapshot snapshot = coAlpha(RoundingMode.HALF_UP);
        CurrencyPolicy cop = snapshot.policy();

        Breakdown breakdown = engine.price(cart(
                new CartLine("BEV-COLA-350", 15),
                new CartLine("SNK-CHIPS-45", 2),
                new CartLine("BEV-COLA-1500", 1)), snapshot);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", cop(cop, "876")),
                        tuple("BEV-COLA-350", cop(cop, "6983")),
                        tuple("SNK-CHIPS-45", cop(cop, "160")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(cop(cop, "46300"), List.of(
                        new Discount(Discount.Kind.COMBO, COMBO_RULE, cop(cop, "1001")),
                        new Discount(Discount.Kind.TIER, COLA_RULE, cop(cop, "750"))),
                List.of(), cop(cop, "44549"), cop(cop, "8019"), cop(cop, "52568")));
    }

    @Test
    void roundsNowhereButAtTheLineDiscountsAndTaxes() {
        // The cases above can show only a rounding that changes a value. Rounding an amount that is
        // already at the currency scale changes nothing, so the places that may round are pinned in
        // the code too: only CurrencyPolicy rounds a BigDecimal, and it is asked to round only by the
        // tier step (RP-2) and by Money.timesRounded, which only the engine calls, for a line's tax
        // (RP-4). A combo's shares are split in whole minor units by largest remainder, without a
        // rounding mode (RP-2). A call, a method reference, and a constructor call or reference all
        // count, so passing policy::round along reaches the rounding just as calling it does.
        JavaClasses pricingDomain = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.orderflow.engine.pricing.domain");

        noClasses().that().doNotBelongToAnyOf(CurrencyPolicy.class)
                .should().accessTargetWhere(ROUNDS_A_BIG_DECIMAL)
                .because("rounding goes only through the country's currency policy (SPEC section 4)")
                .check(pricingDomain);
        noClasses().should().accessTargetWhere(ROUNDS_OUTSIDE_RP2_AND_RP4)
                .because("amounts are rounded only at RP-2 and RP-4 (AC-09)")
                .check(pricingDomain);
        noClasses().that().doNotBelongToAnyOf(PricingEngine.class)
                .should().accessTargetWhere(MULTIPLIES_BY_A_RATE_AND_ROUNDS)
                .because("a rate product is rounded only for a line's tax (RP-4)")
                .check(pricingDomain);
    }

    // A call or a reference to the method or constructor of that name in that class.
    private static boolean reaches(JavaAccess<?> access, Class<?> owner, String name) {
        return access.getTarget() instanceof CodeUnitAccessTarget target
                && target.getOwner().isEquivalentTo(owner)
                && target.getName().equals(name);
    }

    private static boolean takesARoundingSetting(CodeUnitAccessTarget target) {
        List<JavaClass> parameters = target.getRawParameterTypes();
        return parameters.stream().anyMatch(type ->
                        type.isEquivalentTo(RoundingMode.class) || type.isEquivalentTo(MathContext.class))
                || target.getName().equals("setScale") && parameters.size() == 2
                || target.getName().equals("divide") && parameters.size() > 1;
    }
}
