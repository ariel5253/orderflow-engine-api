package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Discount;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep.Units;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC BR-02 and RF-1: marginal brackets, counted on the units given, rounded once per line. */
class TierDiscountStepTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static final UUID GT01_RULE = UUID.fromString("01a13cdb-cc00-7001-8003-000000000001");
    private static final UUID COLAS_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000001");
    private static final UUID SNACKS_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000002");

    private static final Product COLA_350_COP = product("BEV-COLA-350", "colas", "2500", COP);
    private static final Product COLA_350_USD = product("BEV-COLA-350", "colas", "0.60", USD);
    private static final Product COLA_1500_USD = product("BEV-COLA-1500", "colas", "1.45", USD);
    private static final Product PLANTAIN_USD = product("SNK-PLANTAIN-45", "snacks", "0.55", USD);

    // The rule of SPEC BR-02 (GT-01): units 1 to 9 get 0%, 10 to 19 get 5%, 20 on get 10%.
    private static final TierRule GT01 = rule(GT01_RULE, Scope.SKU, "BEV-COLA-350",
            bracket(1, "0.000000"), bracket(10, "0.050000"), bracket(20, "0.100000"));

    // The family rule of the ec-alpha seed: units 12 to 23 get 4%, 24 on get 8%.
    private static final TierRule COLAS = rule(COLAS_RULE, Scope.FAMILY, "colas",
            bracket(1, "0.000000"), bracket(12, "0.040000"), bracket(24, "0.080000"));

    private static Product product(String sku, String family, String listPrice, CurrencyPolicy policy) {
        return new Product(sku, family, new Money(new BigDecimal(listPrice), policy), new BigDecimal("0.190000"));
    }

    private static Bracket bracket(long from, String percent) {
        return new Bracket(from, new BigDecimal(percent));
    }

    private static TierRule rule(UUID id, Scope scope, String target, Bracket... brackets) {
        return new TierRule(id, scope, target, List.of(brackets));
    }

    private static Discount tier(UUID rule, String amount, CurrencyPolicy policy) {
        return new Discount(Discount.Kind.TIER, rule, new Money(new BigDecimal(amount), policy));
    }

    @Test
    void appliesEachBracketOnlyToItsOwnUnits() {
        // GT-01: 10 x 2,500 x 5% + 6 x 2,500 x 10% = 1,250 + 1,500 = 2,750. Stacked percents would
        // give units 20 to 25 15%, 1,250 + 2,250 = 3,500; the top bracket on every unit 6,250.
        Map<String, Discount> discounts = TierDiscountStep.apply(List.of(GT01), List.of(new Units(COLA_350_COP, 25)));

        assertThat(discounts).containsExactly(entry("BEV-COLA-350", tier(GT01_RULE, "2750", COP)));
    }

    @ParameterizedTest(name = "{0} units -> {1}")
    @CsvSource({
        // Unit 10 is the first of the 5% bracket and unit 20 the first of the 10% bracket.
        "10, 125",
        "19, 1250",
        "20, 1500"
    })
    void startsEachBracketAtItsFromUnitInclusive(long units, String discount) {
        Map<String, Discount> discounts = TierDiscountStep.apply(List.of(GT01), List.of(new Units(COLA_350_COP, units)));

        assertThat(discounts).containsExactly(entry("BEV-COLA-350", tier(GT01_RULE, discount, COP)));
    }

    @Test
    void emitsNoDiscountForUnitsThatStayInAZeroPercentBracket() {
        assertThat(TierDiscountStep.apply(List.of(GT01), List.of(new Units(COLA_350_COP, 9)))).isEmpty();
    }

    @Test
    void roundsTheDiscountOnceForTheLineNotOncePerBracket() {
        // RF-1. At 2,505 COP, units 3 and 4 give 2 x 2,505 x 5% = 250.5 and unit 5 gives
        // 2,505 x 10% = 250.5. The line's exact 501.0 rounds to 501. Rounding each bracket would
        // give 251 + 251 = 502.
        Product cola = product("BEV-COLA-350", "colas", "2505", COP);
        TierRule rule = rule(GT01_RULE, Scope.SKU, "BEV-COLA-350",
                bracket(1, "0.000000"), bracket(3, "0.050000"), bracket(5, "0.100000"));

        Map<String, Discount> discounts = TierDiscountStep.apply(List.of(rule), List.of(new Units(cola, 5)));

        assertThat(discounts).containsExactly(entry("BEV-COLA-350", tier(GT01_RULE, "501", COP)));
    }

    @Test
    void emitsNoDiscountThatRoundsToZero() {
        // RP-2: 0.55 x 0.5% = 0.00275, which rounds to 0.00 at the USD scale.
        TierRule rule = rule(SNACKS_RULE, Scope.SKU, "SNK-PLANTAIN-45", bracket(1, "0.005000"));

        assertThat(TierDiscountStep.apply(List.of(rule), List.of(new Units(PLANTAIN_USD, 1)))).isEmpty();
    }

    @Test
    void countsOnlyTheUnitsItIsGiven() {
        // A-04. When 4 of 25 colas are consumed elsewhere, the 21 units left take positions 1 to 21:
        // 10 x 125 + 2 x 250 = 1,750, not the 2,750 of all 25.
        Map<String, Discount> discounts = TierDiscountStep.apply(List.of(GT01), List.of(new Units(COLA_350_COP, 21)));

        assertThat(discounts).containsExactly(entry("BEV-COLA-350", tier(GT01_RULE, "1750", COP)));
        assertThat(TierDiscountStep.apply(List.of(GT01), List.of(new Units(COLA_350_COP, 0)))).isEmpty();
    }

    @Test
    void fillsTheFamilyCountInSkuOrderWhateverTheOrderOfTheUnits() {
        // A-03. BEV-COLA-1500 sorts before BEV-COLA-350, so it takes units 1 to 8 and the colas 350
        // take 9 to 18: units 12 to 18 give 7 x 0.60 x 4% = 0.168 -> 0.17.
        Map<String, Discount> discounts = TierDiscountStep.apply(List.of(COLAS), List.of(
                new Units(COLA_350_USD, 10),
                new Units(COLA_1500_USD, 8)));

        assertThat(discounts).containsExactly(entry("BEV-COLA-350", tier(COLAS_RULE, "0.17", USD)));
    }

    @Test
    void countsEachFamilyOnItsOwn() {
        // 12 colas reach the colas' 4% bracket on their own: 1.45 x 4% = 0.058 -> 0.06. The 2
        // plantain chips start the snacks count at 1, so only the 2nd gets 10%: 0.055 -> 0.06.
        TierRule snacks = rule(SNACKS_RULE, Scope.FAMILY, "snacks", bracket(2, "0.100000"));

        Map<String, Discount> discounts = TierDiscountStep.apply(List.of(COLAS, snacks), List.of(
                new Units(COLA_1500_USD, 12),
                new Units(PLANTAIN_USD, 2)));

        assertThat(discounts).containsOnly(
                entry("BEV-COLA-1500", tier(COLAS_RULE, "0.06", USD)),
                entry("SNK-PLANTAIN-45", tier(SNACKS_RULE, "0.06", USD)));
    }

    @Test
    void givesNothingToUnitsBeforeTheFirstBracket() {
        // A rule whose only bracket starts at unit 5: of 6 units, units 5 and 6 get 10%.
        TierRule rule = rule(GT01_RULE, Scope.SKU, "BEV-COLA-350", bracket(5, "0.100000"));

        assertThat(TierDiscountStep.apply(List.of(rule), List.of(new Units(COLA_350_COP, 6))))
                .containsExactly(entry("BEV-COLA-350", tier(GT01_RULE, "500", COP)));
        assertThat(TierDiscountStep.apply(List.of(rule), List.of(new Units(COLA_350_COP, 4)))).isEmpty();
    }

    @Test
    void givesNothingToAProductNoRuleTargets() {
        TierRule otherSku = rule(GT01_RULE, Scope.SKU, "BEV-COLA-1500", bracket(1, "0.100000"));
        TierRule otherFamily = rule(COLAS_RULE, Scope.FAMILY, "waters", bracket(1, "0.100000"));

        assertThat(TierDiscountStep.apply(List.of(otherSku, otherFamily), List.of(new Units(COLA_350_USD, 30))))
                .isEmpty();
    }

    @Test
    void matchesARuleOnlyWithinItsScope() {
        // A family rule named like the SKU, and a SKU rule named like the family, target neither.
        TierRule familyNamedLikeTheSku = rule(COLAS_RULE, Scope.FAMILY, "BEV-COLA-350", bracket(1, "0.100000"));
        TierRule skuNamedLikeTheFamily = rule(GT01_RULE, Scope.SKU, "colas", bracket(1, "0.100000"));

        assertThat(TierDiscountStep.apply(List.of(familyNamedLikeTheSku, skuNamedLikeTheFamily),
                List.of(new Units(COLA_350_USD, 30)))).isEmpty();
    }

    @Test
    void acceptsZeroUnitsAndRejectsANegativeCount() {
        assertThat(new Units(COLA_350_COP, 0).count()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new Units(COLA_350_COP, -1))
                .withMessageContaining("BEV-COLA-350")
                .withMessageContaining("-1");
        assertThatNullPointerException().isThrownBy(() -> new Units(null, 1)).withMessage("product");
    }
}
