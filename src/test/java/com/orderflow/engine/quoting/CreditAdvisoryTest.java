package com.orderflow.engine.quoting;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * AC-16 and SPEC BR-06: a credit quote reports {@code fits}, {@code available = limit - consumed} and
 * {@code shortfall = max(0, total - available)}, and a total equal to the available credit fits.
 */
class CreditAdvisoryTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    @Test
    void aTotalBelowTheAvailableCreditFitsWithNoShortfall() {
        CreditAdvice advice = CreditAdvice.of(cop("300000"), cop("500000"), cop("100000"));

        assertThat(advice).isEqualTo(new CreditAdvice(true, cop("400000"), cop("0")));
    }

    @Test
    void aTotalEqualToTheAvailableCreditFits() {
        CreditAdvice advice = CreditAdvice.of(cop("400000"), cop("500000"), cop("100000"));

        assertThat(advice).isEqualTo(new CreditAdvice(true, cop("400000"), cop("0")));
    }

    @Test
    void aTotalAboveTheAvailableCreditDoesNotFitAndReportsTheShortfall() {
        CreditAdvice advice = CreditAdvice.of(cop("400001"), cop("500000"), cop("100000"));

        assertThat(advice).isEqualTo(new CreditAdvice(false, cop("400000"), cop("1")));
    }

    @Test
    void theAvailableCreditIsTheLimitMinusWhatIsConsumed() {
        CreditAdvice advice = CreditAdvice.of(usd("0.01"), usd("1500.00"), usd("1500.00"));

        assertThat(advice).isEqualTo(new CreditAdvice(false, usd("0.00"), usd("0.01")));
    }

    @Test
    void theAmountsKeepTheCurrencyScale() {
        CreditAdvice advice = CreditAdvice.of(usd("1234.5"), usd("1500"), usd("200.25"));

        assertThat(advice.available().amount()).isEqualByComparingTo("1299.75");
        assertThat(advice.available().amount().toPlainString()).isEqualTo("1299.75");
        assertThat(advice.shortfall().amount().toPlainString()).isEqualTo("0.00");
        assertThat(advice.fits()).isTrue();
    }

    private static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }
}
