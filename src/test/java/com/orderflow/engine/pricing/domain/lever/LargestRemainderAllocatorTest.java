package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SPEC BR-03 and RF-3: an amount is split in proportion to the weights, each share truncated to the
 * currency scale, the leftover minor units handed to the largest remainders, ties by SKU order, so
 * the shares always sum exactly to the amount.
 */
class LargestRemainderAllocatorTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    // The weights are given in reverse SKU order, so an allocator that follows the order it is
    // given instead of the SKU order fails the tie-break cases.
    private static Map<String, Money> weights(Object... skuAndWeight) {
        Map<String, Money> weights = new LinkedHashMap<>();
        for (int i = skuAndWeight.length - 2; i >= 0; i -= 2) {
            weights.put((String) skuAndWeight[i], (Money) skuAndWeight[i + 1]);
        }
        return weights;
    }

    @Test
    void splitsTheComboDiscountOfSpecBr03() {
        // GT-04: 3,600 over 10,000 and 3,600 gives the exact shares 2,647.06 and 952.94. Truncated
        // they sum 2,647 + 952 = 3,599, and the leftover peso goes to the larger remainder, .94,
        // though the chips sort after the colas.
        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop("3600"), weights(
                "BEV-COLA-350", cop("10000"),
                "SNK-CHIPS-45", cop("3600")));

        assertThat(shares).isEqualTo(Map.of("BEV-COLA-350", cop("2647"), "SNK-CHIPS-45", cop("953")));
    }

    @Test
    void handsTheLeftoverCentToTheLargestRemainderWhateverItsSku() {
        // 1.00 over 0.01, 0.02 and 0.04 (7 cents): 100 / 7 = 14 r 2, 200 / 7 = 28 r 4 and
        // 400 / 7 = 57 r 1. The truncated 99 cents leave one cent for the remainder 4, the second SKU.
        Map<String, Money> shares = LargestRemainderAllocator.allocate(usd("1.00"), weights(
                "BEV-COLA-1500", usd("0.01"),
                "BEV-COLA-350", usd("0.02"),
                "BEV-WATER-600", usd("0.04")));

        assertThat(shares).isEqualTo(Map.of(
                "BEV-COLA-1500", usd("0.14"),
                "BEV-COLA-350", usd("0.29"),
                "BEV-WATER-600", usd("0.57")));
    }

    @Test
    void handsOutSeveralLeftoverUnitsInOrderOfRemainder() {
        // 7 over 2, 1 and 7 (total 10): 14 / 10 = 1 r 4, 7 / 10 = 0 r 7 and 49 / 10 = 4 r 9. The
        // truncated 5 leave 2 units, for the remainders 9 and 7: the last SKU, then the second.
        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop("7"), weights(
                "BEV-COLA-1500", cop("2"),
                "BEV-COLA-350", cop("1"),
                "BEV-WATER-600", cop("7")));

        assertThat(shares).isEqualTo(Map.of(
                "BEV-COLA-1500", cop("1"),
                "BEV-COLA-350", cop("1"),
                "BEV-WATER-600", cop("5")));
    }

    @Test
    void breaksATieBetweenRemaindersBySkuOrder() {
        // 2 over three equal weights is 0.67 each. Truncated they sum 0, and the 2 leftover pesos
        // go to the first two SKUs. Rounding each share HALF_UP would give 1 + 1 + 1 = 3.
        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop("2"), weights(
                "BEV-COLA-1500", cop("2500"),
                "BEV-COLA-350", cop("2500"),
                "BEV-WATER-600", cop("2500")));

        assertThat(shares).isEqualTo(Map.of(
                "BEV-COLA-1500", cop("1"),
                "BEV-COLA-350", cop("1"),
                "BEV-WATER-600", cop("0")));
    }

    @Test
    void splitsExactlyWhenEveryShareIsWhole() {
        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop("300"), weights(
                "BEV-COLA-350", cop("1000"),
                "SNK-CHIPS-45", cop("2000")));

        assertThat(shares).isEqualTo(Map.of("BEV-COLA-350", cop("100"), "SNK-CHIPS-45", cop("200")));
    }

    @Test
    void givesNothingToAZeroWeight() {
        // 5 over 2,500 and 0: the zero weight has a zero remainder, so no leftover reaches it.
        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop("5"), weights(
                "BEV-COLA-350", cop("2500"),
                "BEV-WATER-600", cop("0")));

        assertThat(shares).isEqualTo(Map.of("BEV-COLA-350", cop("5"), "BEV-WATER-600", cop("0")));
    }

    @Test
    void splitsZeroIntoZeros() {
        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop("0"), weights(
                "BEV-COLA-350", cop("2500"),
                "SNK-CHIPS-45", cop("1800")));

        assertThat(shares).isEqualTo(Map.of("BEV-COLA-350", cop("0"), "SNK-CHIPS-45", cop("0")));
    }

    @ParameterizedTest(name = "{0} over {1}")
    @CsvSource(delimiter = '|', value = {
        "1    | 1 1 1",
        "100  | 3 3 3",
        "999  | 7 11 13 17",
        "5000 | 2500 2500 1800",
        "3599 | 10000 3600"
    })
    void sharesAlwaysSumToTheAmount(String amount, String weightList) {
        Map<String, Money> weights = new LinkedHashMap<>();
        List<String> values = Arrays.asList(weightList.split(" "));
        for (int i = 0; i < values.size(); i++) {
            weights.put("SKU-" + i, cop(values.get(i)));
        }

        Map<String, Money> shares = LargestRemainderAllocator.allocate(cop(amount), weights);

        assertThat(shares).containsOnlyKeys(weights.keySet());
        assertThat(shares.values().stream().reduce(cop("0"), Money::plus)).isEqualTo(cop(amount));
    }

    @Test
    void rejectsAnAmountWithoutWeights() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LargestRemainderAllocator.allocate(cop("10"), Map.of()))
                .withMessageContaining("no weight");
    }

    @Test
    void rejectsANegativeAmount() {
        Map<String, Money> weights = Map.of("BEV-COLA-350", cop("2500"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LargestRemainderAllocator.allocate(cop("-1"), weights))
                .withMessageEndingWith(": -1");
    }

    @Test
    void rejectsANegativeWeight() {
        Map<String, Money> weights = weights("BEV-COLA-350", cop("2500"), "SNK-CHIPS-45", cop("-1"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LargestRemainderAllocator.allocate(cop("10"), weights))
                .withMessageContaining("SNK-CHIPS-45")
                .withMessageEndingWith(": -1");
    }

    @Test
    void rejectsWeightsThatAreAllZero() {
        Map<String, Money> weights = weights("BEV-COLA-350", cop("0"), "SNK-CHIPS-45", cop("0"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LargestRemainderAllocator.allocate(cop("10"), weights))
                .withMessageContaining("zero");
    }

    @Test
    void rejectsAWeightInAnotherCurrency() {
        Map<String, Money> weights = Map.of("BEV-COLA-350", usd("0.60"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LargestRemainderAllocator.allocate(cop("10"), weights))
                .withMessageContaining("USD")
                .withMessageContaining("COP");
    }
}
