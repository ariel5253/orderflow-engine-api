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

class ComboAllocationTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final UUID COMBO = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");

    private static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    @Test
    void keepsItsComboAndAmount() {
        ComboAllocation allocation = new ComboAllocation(COMBO, cop("953"));

        assertThat(allocation.comboId()).isEqualTo(COMBO);
        assertThat(allocation.amount()).isEqualTo(cop("953"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void rejectsAnAmountThatIsNotPositive(String amount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ComboAllocation(COMBO, cop(amount)))
                .withMessageContaining(COMBO.toString())
                .withMessageEndingWith(" " + amount);
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException().isThrownBy(() -> new ComboAllocation(null, cop("1")))
                .withMessage("comboId");
        assertThatNullPointerException().isThrownBy(() -> new ComboAllocation(COMBO, null))
                .withMessage("amount");
    }
}
