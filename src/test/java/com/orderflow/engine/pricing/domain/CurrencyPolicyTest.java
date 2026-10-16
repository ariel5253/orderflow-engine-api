package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyPolicyTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    @ParameterizedTest
    @CsvSource({
            "11352.50, 11353",
            "11352.49, 11352",
            "0.5, 1",
            "-0.5, -1",
            "2500, 2500"})
    void roundsHalfUpAtScaleZero(String exact, String expected) {
        assertThat(COP.round(new BigDecimal(exact))).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "1.005, 1.01",
            "1.0049, 1.00",
            "2.675, 2.68",
            "-1.005, -1.01",
            "7.5, 7.50"})
    void roundsHalfUpAtScaleTwo(String exact, String expected) {
        assertThat(USD.round(new BigDecimal(exact))).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "2.5, 2",
            "3.5, 4"})
    void roundsWithTheModeOfThePolicy(String exact, String expected) {
        CurrencyPolicy halfEven = new CurrencyPolicy("COP", 0, RoundingMode.HALF_EVEN);

        assertThat(halfEven.round(new BigDecimal(exact))).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void copScaleComesFromCountryDataAndNotFromTheJdkCurrencyTable() {
        assertThat(Currency.getInstance("COP").getDefaultFractionDigits()).isEqualTo(2);

        assertThat(COP.scale()).isZero();
        assertThat(COP.round(new BigDecimal("2500.50"))).isEqualTo(new BigDecimal("2501"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void acceptsScalesFromZeroToFour(int scale) {
        assertThat(new CurrencyPolicy("USD", scale, RoundingMode.HALF_UP).scale()).isEqualTo(scale);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 5})
    void rejectsAScaleOutsideZeroToFour(int scale) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CurrencyPolicy("USD", scale, RoundingMode.HALF_UP))
                .withMessageContaining(String.valueOf(scale));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "US", "USDX", "usd", "U5D"})
    void rejectsACurrencyCodeThatIsNotThreeUppercaseLetters(String code) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CurrencyPolicy(code, 2, RoundingMode.HALF_UP))
                .withMessageContaining("three uppercase letters");
    }

    @Test
    void rejectsARoundingModeThatCannotRound() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CurrencyPolicy("USD", 2, RoundingMode.UNNECESSARY))
                .withMessageContaining("UNNECESSARY");
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CurrencyPolicy(null, 2, RoundingMode.HALF_UP))
                .withMessage("currencyCode");
        assertThatNullPointerException()
                .isThrownBy(() -> new CurrencyPolicy("USD", 2, null))
                .withMessage("roundingMode");
    }
}
