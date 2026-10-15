package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** SPEC BR-04 and RF-4: the country's policy decides the taxable base of a gift line. */
class GiftTaxPolicyTest {

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);
    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);

    @Test
    void anExemptGiftHasNoTaxableBaseInItsOwnCurrency() {
        assertThat(GiftTaxPolicy.EXEMPT.taxableBase(new Money(new BigDecimal("0.90"), USD)))
                .isEqualTo(new Money(new BigDecimal("0.00"), USD));
        assertThat(GiftTaxPolicy.EXEMPT.taxableBase(new Money(new BigDecimal("3000"), COP)))
                .isEqualTo(new Money(BigDecimal.ZERO, COP));
    }

    @Test
    void aTaxedGiftIsTaxedOnItsReferenceValue() {
        assertThat(GiftTaxPolicy.TAXED.taxableBase(new Money(new BigDecimal("0.90"), USD)))
                .isEqualTo(new Money(new BigDecimal("0.90"), USD));
    }
}
