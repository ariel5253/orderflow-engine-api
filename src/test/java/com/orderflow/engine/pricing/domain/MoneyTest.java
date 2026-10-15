package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    @Test
    void keepsTheAmountAtThePolicyScale() {
        assertThat(usd("1.5").amount()).isEqualTo(new BigDecimal("1.50"));
        assertThat(usd("3").amount()).isEqualTo(new BigDecimal("3.00"));
        assertThat(cop("71103").amount().toPlainString()).isEqualTo("71103");
        assertThat(cop("2.5E+3").amount().toPlainString()).isEqualTo("2500");
    }

    @Test
    void acceptsAnAmountAtExactlyThePolicyScale() {
        assertThat(usd("10.25").amount()).isEqualTo(new BigDecimal("10.25"));
    }

    @ParameterizedTest
    @CsvSource({
            "2500.5, 0",
            "2500.00, 0",
            "10.005, 2"})
    void rejectsAnAmountWithAScaleAboveThePolicy(String amount, int scale) {
        CurrencyPolicy policy = new CurrencyPolicy("XTS", scale, RoundingMode.HALF_UP);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal(amount), policy))
                .withMessageContaining(amount)
                .withMessageContaining("scale " + scale);
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Money(null, USD))
                .withMessage("amount");
        assertThatNullPointerException()
                .isThrownBy(() -> new Money(BigDecimal.ONE, null))
                .withMessage("policy");
    }

    @Test
    void zeroIsAtThePolicyScale() {
        assertThat(Money.zero(USD)).isEqualByComparingTo(usd("0.00"));
        assertThat(Money.zero(USD).amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(Money.zero(COP).amount()).isEqualTo(new BigDecimal("0"));
    }

    @Test
    void addsAndSubtractsExactly() {
        assertThat(usd("10.25").plus(usd("0.75"))).isEqualByComparingTo(usd("11.00"));
        assertThat(usd("10.25").minus(usd("0.75"))).isEqualByComparingTo(usd("9.50"));
        assertThat(usd("0.75").minus(usd("10.25"))).isEqualByComparingTo(usd("-9.50"));
        assertThat(cop("62500").minus(cop("2750"))).isEqualByComparingTo(cop("59750"));
    }

    @Test
    void sumsRoundedLinesWithoutRoundingTheTotalAgain() {
        Money total = Money.zero(USD)
                .plus(usd("0.33"))
                .plus(usd("0.33"))
                .plus(usd("0.34"));

        assertThat(total).isEqualByComparingTo(usd("1.00"));
    }

    @Test
    void multipliesByAQuantityExactly() {
        assertThat(cop("2500").times(25)).isEqualByComparingTo(cop("62500"));
        assertThat(usd("1.25").times(3)).isEqualByComparingTo(usd("3.75"));
        assertThat(usd("1.25").times(3).amount().scale()).isEqualTo(2);
    }

    @Test
    void multipliesByARateAndRoundsOnceWithThePolicy() {
        BigDecimal vat = new BigDecimal("0.19");
        BigDecimal fifteenPercent = new BigDecimal("0.15");

        assertThat(cop("59750").timesRounded(vat)).isEqualByComparingTo(cop("11353"));
        assertThat(usd("10.05").timesRounded(fifteenPercent)).isEqualByComparingTo(usd("1.51"));
        assertThat(usd("10.03").timesRounded(fifteenPercent)).isEqualByComparingTo(usd("1.50"));
    }

    @Test
    void roundsARateProductWithTheModeOfThePolicy() {
        CurrencyPolicy halfEven = new CurrencyPolicy("COP", 0, RoundingMode.HALF_EVEN);
        Money base = new Money(new BigDecimal("50"), halfEven);

        assertThat(base.timesRounded(new BigDecimal("0.05")).amount()).isEqualTo(new BigDecimal("2"));
    }

    @Test
    void compareToIgnoresTrailingZeros() {
        Money oneAndAHalf = usd("1.5");
        Money oneFifty = usd("1.50");

        assertThat(new BigDecimal("1.5")).isNotEqualTo(new BigDecimal("1.50"));
        assertThat(oneAndAHalf.compareTo(oneFifty)).isZero();
    }

    @Test
    void equalsAgreesWithCompareToBecauseTheAmountIsAtThePolicyScale() {
        Money oneAndAHalf = usd("1.5");
        Money oneFifty = usd("1.50");

        assertThat(oneAndAHalf).isEqualTo(oneFifty).hasSameHashCodeAs(oneFifty);
    }

    @Test
    void compareToOrdersByAmount() {
        assertThat(usd("1.49").compareTo(usd("1.50"))).isNegative();
        assertThat(usd("1.51").compareTo(usd("1.50"))).isPositive();
        assertThat(cop("-1").compareTo(cop("0"))).isNegative();
    }

    @Test
    void mixingCurrenciesThrows() {
        Money pesos = cop("2500");
        Money dollars = usd("2500");

        assertThatIllegalArgumentException().isThrownBy(() -> pesos.plus(dollars))
                .withMessageContaining("COP").withMessageContaining("USD");
        assertThatIllegalArgumentException().isThrownBy(() -> pesos.minus(dollars))
                .withMessageContaining("COP").withMessageContaining("USD");
        assertThatIllegalArgumentException().isThrownBy(() -> pesos.compareTo(dollars))
                .withMessageContaining("COP").withMessageContaining("USD");
    }

    @Test
    void mixingPoliciesOfTheSameCurrencyThrows() {
        Money halfUp = cop("2500");
        Money halfEven = new Money(new BigDecimal("2500"), new CurrencyPolicy("COP", 0, RoundingMode.HALF_EVEN));

        assertThatIllegalArgumentException().isThrownBy(() -> halfUp.plus(halfEven))
                .withMessageContaining("HALF_UP").withMessageContaining("HALF_EVEN");
    }
}
