package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiscountTest {

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);
    private static final UUID RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000001");

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    @Test
    void showsItsOriginAsTheLeverAndTheRule() {
        Discount discount = new Discount(Discount.Kind.TIER, RULE, usd("0.01"));

        assertThat(discount.origin()).isEqualTo("TIER:01a13cdb-cc00-7002-8003-000000000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01"})
    void rejectsAnAmountThatIsNotPositive(String amount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Discount(Discount.Kind.TIER, RULE, usd(amount)))
                .withMessageContaining("TIER:" + RULE);
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException().isThrownBy(() -> new Discount(null, RULE, usd("0.01")))
                .withMessage("kind");
        assertThatNullPointerException().isThrownBy(() -> new Discount(Discount.Kind.TIER, null, usd("0.01")))
                .withMessage("ruleId");
        assertThatNullPointerException().isThrownBy(() -> new Discount(Discount.Kind.TIER, RULE, null))
                .withMessage("amount");
    }
}
