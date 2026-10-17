package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.orderflow.engine.pricing.domain.CartRejectedException;
import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Gift;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.Trigger;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep.Units;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SPEC BR-04, step 1 of BR-01: a gift rule is evaluated on the paid units and on their gross before
 * discounts. It applies once for each whole multiple of its condition when it is repeatable, at most
 * once otherwise, and the gifts come in rule-id order. A gift quantity above 1,000,000 is rejected
 * as BR-00 rejects a line quantity, and deriving it never overflows.
 */
class GiftStepTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);

    private static final UUID SNACKS_GIFT = UUID.fromString("01a13cdb-cc00-7001-8005-000000000001");
    // Its first byte is above 0x7f: it sorts after SNACKS_GIFT as text and as PostgreSQL orders uuid
    // values, while UUID.compareTo, which compares signed longs, would put it first.
    private static final UUID LATE_GIFT = UUID.fromString("81a13cdb-cc00-7001-8005-000000000002");

    private static final Product COLA = product("BEV-COLA-350", "colas", "2500", "0.190000");
    private static final Product COLA_1500 = product("BEV-COLA-1500", "colas", "5200", "0.190000");
    private static final Product CHIPS = product("SNK-CHIPS-45", "snacks", "1800", "0.050000");
    private static final Product WATER = product("BEV-WATER-600", "waters", "1500", "0.190000");

    private static Product product(String sku, String family, String listPrice, String taxRate) {
        return new Product(sku, family, new Money(new BigDecimal(listPrice), COP), new BigDecimal(taxRate));
    }

    // A rule whose gift is waters 600.
    private static GiftRule waterRule(Trigger trigger, long giftQty, boolean repeatable) {
        return new GiftRule(SNACKS_GIFT, trigger, "BEV-WATER-600", giftQty, repeatable);
    }

    private static Gift waters(long qty) {
        return new Gift(SNACKS_GIFT, "BEV-WATER-600", qty);
    }

    @ParameterizedTest(name = "{0} chips give {1} waters")
    @CsvSource({"10, 1", "19, 1", "20, 2", "25, 2"})
    void grantsARepeatableGiftOncePerWholeMultipleOfItsMinimumQuantity(long chips, long waters) {
        // The 3 paid waters are not snacks, so they do not count.
        List<Gift> gifts = GiftStep.apply(List.of(waterRule(new FamilyQuantity("snacks", 10), 1, true)),
                List.of(new Units(WATER, 3), new Units(CHIPS, chips)));

        assertThat(gifts).containsExactly(waters(waters));
    }

    @Test
    void grantsANonRepeatableGiftOnceHoweverManyMultiplesThePaidUnitsHold() {
        // 25 chips hold 2 multiples of 10, and each application gives 2 waters: once for a rule that
        // does not repeat, twice for one that does.
        List<Units> paid = List.of(new Units(CHIPS, 25));

        assertThat(GiftStep.apply(List.of(waterRule(new FamilyQuantity("snacks", 10), 2, false)), paid))
                .containsExactly(waters(2));
        assertThat(GiftStep.apply(List.of(waterRule(new FamilyQuantity("snacks", 10), 2, true)), paid))
                .containsExactly(waters(4));
    }

    @Test
    void countsThePaidUnitsOfEverySkuOfAFamilyTogetherAndOfOneSkuAlone() {
        // 6 colas 350 and 4 colas 1500 are 10 colas, which meets the family condition. A condition on
        // the cola 350 counts only its own 6 units.
        List<Units> paid = List.of(new Units(COLA_1500, 4), new Units(COLA, 6), new Units(CHIPS, 12));

        assertThat(GiftStep.apply(List.of(waterRule(new FamilyQuantity("colas", 10), 1, true)), paid))
                .containsExactly(waters(1));
        assertThat(GiftStep.apply(List.of(waterRule(new SkuQuantity("BEV-COLA-350", 10), 1, true)), paid))
                .isEmpty();
        assertThat(GiftStep.apply(List.of(waterRule(new SkuQuantity("BEV-COLA-350", 6), 1, true)), paid))
                .containsExactly(waters(1));
    }

    @ParameterizedTest(name = "{0} colas and {1} chips give {2} waters")
    @CsvSource({"20, 0, 1", "19, 2, 1", "39, 3, 2"})
    void grantsAGrossSubtotalGiftOncePerWholeMultipleOfItsMinimumGross(long colas, long chips, long waters) {
        // Colas list at 2,500 and chips at 1,800, so the carts list at 50,000, 47,500 + 3,600 = 51,100
        // and 97,500 + 5,400 = 102,900: 1, 1 and 2 whole multiples of 50,000.
        List<Gift> gifts = GiftStep.apply(
                List.of(waterRule(new GrossSubtotal(new Money(new BigDecimal("50000"), COP)), 1, true)),
                List.of(new Units(COLA, colas), new Units(CHIPS, chips)));

        assertThat(gifts).containsExactly(waters(waters));
    }

    @Test
    void grantsNothingBelowTheMinimum() {
        // 9 chips are below the 10 of the snacks condition. 19 colas and 1 chips list at
        // 47,500 + 1,800 = 49,300, below the 50,000 of the gross condition.
        assertThat(GiftStep.apply(List.of(waterRule(new FamilyQuantity("snacks", 10), 1, true)),
                List.of(new Units(CHIPS, 9)))).isEmpty();
        assertThat(GiftStep.apply(
                List.of(waterRule(new GrossSubtotal(new Money(new BigDecimal("50000"), COP)), 1, true)),
                List.of(new Units(COLA, 19), new Units(CHIPS, 1)))).isEmpty();
    }

    @Test
    void grantsNothingWithoutGiftRulesOrWhenTheConditionSkuIsNotPaid() {
        List<Units> paid = List.of(new Units(CHIPS, 25));

        assertThat(GiftStep.apply(List.of(), paid)).isEmpty();
        assertThat(GiftStep.apply(List.of(waterRule(new SkuQuantity("BEV-TEA-500", 1), 1, true)), paid)).isEmpty();
    }

    @ParameterizedTest(name = "{0} applications of {1} waters")
    @CsvSource({"1000000, 1", "500000, 2", "1, 1000000"})
    void grantsAGiftOfExactlyTheBound(long chips, long giftQty) {
        List<Gift> gifts = GiftStep.apply(List.of(waterRule(new SkuQuantity("SNK-CHIPS-45", 1), giftQty, true)),
                List.of(new Units(CHIPS, chips)));

        assertThat(gifts).containsExactly(waters(1_000_000L));
    }

    @Test
    void rejectsAGiftAboveTheBoundThatLinesWithinTheBoundDerive() {
        // Each cola line holds at most the bound, but the family counts 600,000 + 400,001 units, one gift each.
        List<Units> paid = List.of(new Units(COLA_1500, 400_001), new Units(COLA, 600_000));

        assertGiftRejected(() -> GiftStep.apply(List.of(waterRule(new FamilyQuantity("colas", 1), 1, true)), paid));
    }

    @Test
    void rejectsARepeatableGiftThatTheMultiplesPushAboveTheBound() {
        // 500,001 applications of 2 waters are 1,000,002 waters.
        assertGiftRejected(() -> GiftStep.apply(List.of(waterRule(new SkuQuantity("SNK-CHIPS-45", 1), 2, true)),
                List.of(new Units(CHIPS, 500_001))));
    }

    @Test
    void rejectsANonRepeatableGiftWhoseOwnQuantityIsAboveTheBound() {
        assertGiftRejected(() -> GiftStep.apply(
                List.of(waterRule(new SkuQuantity("SNK-CHIPS-45", 1), 1_000_001L, false)),
                List.of(new Units(CHIPS, 1))));
    }

    @Test
    void rejectsAGiftQuantityThatDoesNotFitALongInsteadOfOverflowing() {
        // Two applications of Long.MAX_VALUE waters would wrap around to a negative quantity.
        assertGiftRejected(() -> GiftStep.apply(
                List.of(waterRule(new SkuQuantity("SNK-CHIPS-45", 1), Long.MAX_VALUE, true)),
                List.of(new Units(CHIPS, 2))));
    }

    @Test
    void countsTheMultiplesOfAGrossSubtotalThatDoNotFitALongWithoutFailing() {
        // A million units at 999,999,999,999,999 hold about 10^21 multiples of a gross of 1, more than a long holds:
        // a repeatable rule derives a gift above the bound, and a rule that does not repeat still gives its gift once.
        Product costly = product("BEV-COLA-1500", "colas", "999999999999999", "0.190000");
        List<Units> paid = List.of(new Units(costly, 1_000_000));
        GrossSubtotal fromOne = new GrossSubtotal(new Money(BigDecimal.ONE, COP));

        assertGiftRejected(() -> GiftStep.apply(List.of(waterRule(fromOne, 1, true)), paid));
        assertThat(GiftStep.apply(List.of(waterRule(fromOne, 1, false)), paid)).containsExactly(waters(1));
    }

    @Test
    void listsTheGiftsInRuleIdOrder() {
        // Both rules apply to 20 chips. The late rule is listed first, but its id sorts second.
        GiftRule late = new GiftRule(LATE_GIFT, new SkuQuantity("SNK-CHIPS-45", 1), "BEV-COLA-350", 1, false);
        GiftRule snacks = waterRule(new FamilyQuantity("snacks", 10), 1, true);

        List<Gift> gifts = GiftStep.apply(List.of(late, snacks), List.of(new Units(CHIPS, 20)));

        assertThat(gifts).containsExactly(waters(2), new Gift(LATE_GIFT, "BEV-COLA-350", 1));
    }

    // The rejection is the one of BR-00, and its log message names the rule.
    private static void assertGiftRejected(ThrowingCallable apply) {
        assertThatExceptionOfType(CartRejectedException.class)
                .isThrownBy(apply)
                .satisfies(rejection -> assertThat(rejection.code()).isEqualTo(Code.INVALID_QUANTITY))
                .withMessageContaining(SNACKS_GIFT.toString());
    }
}
