package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.orderflow.engine.pricing.domain.LineBreakdown.GiftReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GiftTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final UUID RULE = UUID.fromString("01a13cdb-cc00-7001-8005-000000000001");

    @Test
    void formatsTheOriginOfAGiftRule() {
        assertThat(Gift.origin(RULE)).isEqualTo("GIFT:01a13cdb-cc00-7001-8005-000000000001");
    }

    @Test
    void showsItsOriginAsTheGiftRule() {
        Gift gift = new Gift(RULE, "BEV-WATER-600", 2);

        assertThat(gift.origin()).isEqualTo("GIFT:01a13cdb-cc00-7001-8005-000000000001");
        assertThat(gift.sku()).isEqualTo("BEV-WATER-600");
        assertThat(gift.qty()).isEqualTo(2L);
    }

    @Test
    void givesAtLeastOneUnit() {
        assertThat(new Gift(RULE, "BEV-WATER-600", 1).qty()).isEqualTo(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> new Gift(RULE, "BEV-WATER-600", 0))
                .withMessageContaining("BEV-WATER-600")
                .withMessageEndingWith(": 0");
    }

    @Test
    void aGiftLineShowsTheSameOriginAndItsReferenceValue() {
        Money referenceValue = new Money(new BigDecimal("3000"), COP);
        GiftReference reference = new GiftReference(RULE, referenceValue);

        assertThat(reference.origin()).isEqualTo("GIFT:01a13cdb-cc00-7001-8005-000000000001");
        assertThat(reference.referenceValue()).isEqualTo(referenceValue);
    }

    @Test
    void rejectsMissingValues() {
        Money referenceValue = new Money(new BigDecimal("3000"), COP);

        assertThatNullPointerException().isThrownBy(() -> new Gift(null, "BEV-WATER-600", 1)).withMessage("ruleId");
        assertThatNullPointerException().isThrownBy(() -> new Gift(RULE, null, 1)).withMessage("sku");
        assertThatNullPointerException().isThrownBy(() -> new GiftReference(null, referenceValue))
                .withMessage("ruleId");
        assertThatNullPointerException().isThrownBy(() -> new GiftReference(RULE, null))
                .withMessage("referenceValue");
    }
}
