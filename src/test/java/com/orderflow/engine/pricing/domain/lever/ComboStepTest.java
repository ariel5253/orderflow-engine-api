package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.pricing.domain.ComboAllocation;
import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep.Units;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC BR-03, RF-2 and RF-3: a combo consumes whole applications of its required set, a combo that
 * saves nothing consumes nothing, overlapping combos run in id order on the units left, and each
 * combo's discount is split over its lines by largest remainder.
 */
class ComboStepTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);

    private static final UUID GT04_COMBO = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");
    private static final UUID WATER_COMBO = UUID.fromString("01a13cdb-cc00-7001-8004-000000000003");
    // Its first byte is above 0x7f: it sorts after GT04_COMBO as text and as PostgreSQL orders uuid
    // values, while UUID.compareTo, which compares signed longs, would put it first.
    private static final UUID COLAS_COMBO = UUID.fromString("81a13cdb-cc00-7001-8004-000000000002");

    private static final Product COLA = product("BEV-COLA-350", "colas", "2500", "0.190000");
    private static final Product CHIPS = product("SNK-CHIPS-45", "snacks", "1800", "0.050000");
    private static final Product WATER = product("BEV-WATER-600", "waters", "1500", "0.190000");

    private static Product product(String sku, String family, String listPrice, String taxRate) {
        return new Product(sku, family, cop(listPrice), new BigDecimal(taxRate));
    }

    private static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    // The combo of SPEC BR-03: 2 colas and 1 chips, listed at 5,000 + 1,800 = 6,800.
    private static ComboRule gt04Combo(String price) {
        return new ComboRule(GT04_COMBO, cop(price),
                List.of(new Item("BEV-COLA-350", 2), new Item("SNK-CHIPS-45", 1)));
    }

    // 3 colas, listed at 7,500, for 7,000.
    private static ComboRule colasCombo() {
        return new ComboRule(COLAS_COMBO, cop("7000"), List.of(new Item("BEV-COLA-350", 3)));
    }

    private static ComboAllocation part(UUID combo, String amount) {
        return new ComboAllocation(combo, cop(amount));
    }

    @Test
    void consumesWholeApplicationsAndSplitsTheDiscountByTheGrossOfTheConsumedUnits() {
        // 5 colas and 3 chips hold 2 applications, limited by the colas: 4 colas (10,000) and 2 chips
        // (3,600) for 10,000, a discount of 3,600. It is split on the consumed 10,000 and 3,600, as in
        // GT-04: 2,647 and 953. Split on the line gross of 12,500 and 5,400 it would be 2,514 and
        // 1,086.
        ComboStep.Result result = ComboStep.apply(List.of(gt04Combo("5000")),
                List.of(new Units(COLA, 5), new Units(CHIPS, 3)));

        assertThat(result.allocationsOf("BEV-COLA-350")).containsExactly(part(GT04_COMBO, "2647"));
        assertThat(result.allocationsOf("SNK-CHIPS-45")).containsExactly(part(GT04_COMBO, "953"));
        assertThat(result.unconsumed()).containsExactly(new Units(COLA, 1), new Units(CHIPS, 1));
    }

    @ParameterizedTest(name = "combo price {0}")
    @ValueSource(strings = {"6800", "6801"})
    void aComboThatSavesNothingConsumesNothing(String price) {
        // One application lists at 6,800: a combo price of 6,800 or more saves nothing.
        ComboStep.Result result = ComboStep.apply(List.of(gt04Combo(price)),
                List.of(new Units(COLA, 2), new Units(CHIPS, 1)));

        assertThat(result.allocations()).isEmpty();
        assertThat(result.unconsumed()).containsExactly(new Units(COLA, 2), new Units(CHIPS, 1));
    }

    @Test
    void aComboThatSavesOnePesoConsumesItsUnitsAndEmitsNoZeroAllocation() {
        // At 6,799 the combo saves 1. The colas' share 1 x 5,000 / 6,800 has the larger remainder and
        // takes it; the chips' share rounds to 0 and is not emitted (RP-2).
        ComboStep.Result result = ComboStep.apply(List.of(gt04Combo("6799")),
                List.of(new Units(COLA, 2), new Units(CHIPS, 1)));

        assertThat(result.allocationsOf("BEV-COLA-350")).containsExactly(part(GT04_COMBO, "1"));
        assertThat(result.allocationsOf("SNK-CHIPS-45")).isEmpty();
        assertThat(result.unconsumed()).containsExactly(new Units(COLA, 0), new Units(CHIPS, 0));
    }

    @Test
    void appliesNoComboWhenARequiredSkuIsMissingOrShort() {
        ComboStep.Result withoutChips = ComboStep.apply(List.of(gt04Combo("5000")),
                List.of(new Units(COLA, 4)));
        ComboStep.Result oneCola = ComboStep.apply(List.of(gt04Combo("5000")),
                List.of(new Units(COLA, 1), new Units(CHIPS, 5)));

        assertThat(withoutChips.allocations()).isEmpty();
        assertThat(withoutChips.unconsumed()).containsExactly(new Units(COLA, 4));
        assertThat(oneCola.allocations()).isEmpty();
        assertThat(oneCola.unconsumed()).containsExactly(new Units(COLA, 1), new Units(CHIPS, 5));
    }

    @Test
    void appliesNothingWithoutComboRules() {
        ComboStep.Result result = ComboStep.apply(List.of(),
                List.of(new Units(COLA, 4), new Units(CHIPS, 2)));

        assertThat(result.allocations()).isEmpty();
        assertThat(result.unconsumed()).containsExactly(new Units(COLA, 4), new Units(CHIPS, 2));
    }

    @Test
    void evaluatesOverlappingCombosInIdOrderOnTheUnitsLeft() {
        // GT04_COMBO runs first though it is listed second: 1 application consumes 2 of the 4 colas
        // and the chips, for 6,800 - 5,000 = 1,800. Split on 5,000 and 1,800: 1,323 r 3,600 and
        // 476 r 3,200 (over 6,800), so the leftover peso goes to the colas: 1,324 and 476. The 2
        // colas left cannot fill the colas combo. Run first, the colas combo would take 3 colas for a
        // discount of 500 and leave the other combo short.
        ComboStep.Result result = ComboStep.apply(List.of(colasCombo(), gt04Combo("5000")),
                List.of(new Units(COLA, 4), new Units(CHIPS, 1)));

        assertThat(result.allocationsOf("BEV-COLA-350")).containsExactly(part(GT04_COMBO, "1324"));
        assertThat(result.allocationsOf("SNK-CHIPS-45")).containsExactly(part(GT04_COMBO, "476"));
        assertThat(result.unconsumed()).containsExactly(new Units(COLA, 2), new Units(CHIPS, 0));
    }

    @Test
    void aLaterComboGetsTheUnitsOfAnEarlierComboThatSavesNothing() {
        // GT04_COMBO at 6,800 saves nothing and consumes nothing, so the colas combo takes 3 of the 4
        // colas: a discount of 7,500 - 7,000 = 500, all of it on the colas.
        ComboStep.Result result = ComboStep.apply(List.of(gt04Combo("6800"), colasCombo()),
                List.of(new Units(COLA, 4), new Units(CHIPS, 1)));

        assertThat(result.allocationsOf("BEV-COLA-350")).containsExactly(part(COLAS_COMBO, "500"));
        assertThat(result.allocationsOf("SNK-CHIPS-45")).isEmpty();
        assertThat(result.unconsumed()).containsExactly(new Units(COLA, 1), new Units(CHIPS, 1));
    }

    @Test
    void listsTheAllocationsOfASkuInSeveralCombosInComboOrder() {
        // GT04_COMBO takes 2 colas and the chips: 1,324 and 476, as above. The water combo then takes
        // the last cola and the water, listed at 2,500 + 1,500 = 4,000, for 3,500. Its 500 splits
        // into 312.5 and 187.5: both truncate with the remainder 2,000 (over 4,000), and the tie goes
        // to the first SKU, the cola: 313 and 187.
        ComboRule waterCombo = new ComboRule(WATER_COMBO, cop("3500"),
                List.of(new Item("BEV-WATER-600", 1), new Item("BEV-COLA-350", 1)));

        ComboStep.Result result = ComboStep.apply(List.of(waterCombo, gt04Combo("5000")),
                List.of(new Units(COLA, 3), new Units(WATER, 1), new Units(CHIPS, 1)));

        assertThat(result.allocationsOf("BEV-COLA-350"))
                .containsExactly(part(GT04_COMBO, "1324"), part(WATER_COMBO, "313"));
        assertThat(result.allocationsOf("SNK-CHIPS-45")).containsExactly(part(GT04_COMBO, "476"));
        assertThat(result.allocationsOf("BEV-WATER-600")).containsExactly(part(WATER_COMBO, "187"));
        assertThat(result.unconsumed())
                .containsExactly(new Units(COLA, 0), new Units(WATER, 0), new Units(CHIPS, 0));
    }
}
