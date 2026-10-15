package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GiftRuleTest {

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);
    private static final UUID ID = UUID.fromString("01a13cdb-cc00-7002-8005-000000000001");
    private static final SkuQuantity TEN_CHIPS = new SkuQuantity("SNK-CHIPS-45", 10);

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    @Test
    void keepsItsConditionAndItsGift() {
        GiftRule rule = new GiftRule(ID, new FamilyQuantity("snacks", 10), "BEV-WATER-600", 2, true);

        assertThat(rule.trigger()).isEqualTo(new FamilyQuantity("snacks", 10));
        assertThat(rule.giftSku()).isEqualTo("BEV-WATER-600");
        assertThat(rule.giftQty()).isEqualTo(2L);
        assertThat(rule.repeatable()).isTrue();
    }

    @Test
    void givesAtLeastOneUnit() {
        assertThat(new GiftRule(ID, TEN_CHIPS, "BEV-WATER-600", 1, false).giftQty()).isEqualTo(1L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GiftRule(ID, TEN_CHIPS, "BEV-WATER-600", 0, false))
                .withMessageContaining(ID.toString())
                .withMessageEndingWith(": 0");
    }

    @Test
    void aQuantityConditionNeedsAtLeastOneUnit() {
        assertThat(new SkuQuantity("SNK-CHIPS-45", 1).minQty()).isEqualTo(1L);
        assertThat(new FamilyQuantity("snacks", 1).minQty()).isEqualTo(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> new SkuQuantity("SNK-CHIPS-45", 0))
                .withMessageContaining("SNK-CHIPS-45")
                .withMessageEndingWith(": 0");
        assertThatIllegalArgumentException().isThrownBy(() -> new FamilyQuantity("snacks", 0))
                .withMessageContaining("snacks")
                .withMessageEndingWith(": 0");
    }

    @Test
    void acceptsTheSmallestGrossSubtotalAboveZero() {
        assertThat(new GrossSubtotal(usd("0.01")).minGross()).isEqualTo(usd("0.01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-0.01"})
    void rejectsAGrossSubtotalThatIsNotAboveZero(String minGross) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GrossSubtotal(usd(minGross)))
                .withMessageEndingWith(": " + minGross);
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException().isThrownBy(() -> new GiftRule(null, TEN_CHIPS, "BEV-WATER-600", 1, false))
                .withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> new GiftRule(ID, null, "BEV-WATER-600", 1, false))
                .withMessage("trigger");
        assertThatNullPointerException().isThrownBy(() -> new GiftRule(ID, TEN_CHIPS, null, 1, false))
                .withMessage("giftSku");
        assertThatNullPointerException().isThrownBy(() -> new SkuQuantity(null, 10)).withMessage("sku");
        assertThatNullPointerException().isThrownBy(() -> new FamilyQuantity(null, 10)).withMessage("family");
        assertThatNullPointerException().isThrownBy(() -> new GrossSubtotal(null)).withMessage("minGross");
    }
}
