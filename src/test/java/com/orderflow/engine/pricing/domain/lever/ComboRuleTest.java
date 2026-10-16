package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComboRuleTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final UUID ID = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");

    private static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    @Test
    void keepsItsItemsInSkuOrder() {
        ComboRule rule = new ComboRule(ID, cop("5000"), List.of(
                new Item("SNK-CHIPS-45", 1),
                new Item("BEV-COLA-350", 2)));

        assertThat(rule.items()).containsExactly(new Item("BEV-COLA-350", 2), new Item("SNK-CHIPS-45", 1));
        assertThat(rule.comboPrice()).isEqualTo(cop("5000"));
    }

    @Test
    void keepsItsItemsWhateverHappensToTheGivenList() {
        List<Item> items = new ArrayList<>(List.of(new Item("BEV-COLA-350", 2)));

        ComboRule rule = new ComboRule(ID, cop("5000"), items);
        items.add(new Item("SNK-CHIPS-45", 1));

        assertThat(rule.items()).containsExactly(new Item("BEV-COLA-350", 2));
    }

    @Test
    void acceptsAFreeCombo() {
        assertThat(new ComboRule(ID, cop("0"), List.of(new Item("BEV-COLA-350", 2))).comboPrice())
                .isEqualTo(cop("0"));
    }

    @Test
    void rejectsANegativePrice() {
        List<Item> items = List.of(new Item("BEV-COLA-350", 2));

        assertThatIllegalArgumentException().isThrownBy(() -> new ComboRule(ID, cop("-1"), items))
                .withMessageContaining(ID.toString())
                .withMessageEndingWith(": -1");
    }

    @Test
    void rejectsAComboWithoutItems() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ComboRule(ID, cop("5000"), List.of()))
                .withMessageContaining(ID.toString());
    }

    @Test
    void rejectsASkuListedTwice() {
        List<Item> items = List.of(new Item("BEV-COLA-350", 2), new Item("BEV-COLA-350", 1));

        assertThatIllegalArgumentException().isThrownBy(() -> new ComboRule(ID, cop("5000"), items))
                .withMessageContaining(ID.toString())
                .withMessageContaining("BEV-COLA-350");
    }

    @Test
    void acceptsAnItemOfOneUnitAndRejectsOneOfFewer() {
        assertThat(new Item("BEV-COLA-350", 1).minQty()).isEqualTo(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> new Item("BEV-COLA-350", 0))
                .withMessageContaining("BEV-COLA-350")
                .withMessageEndingWith(": 0");
    }

    @Test
    void rejectsMissingValues() {
        List<Item> items = List.of(new Item("BEV-COLA-350", 2));

        assertThatNullPointerException().isThrownBy(() -> new ComboRule(null, cop("5000"), items))
                .withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> new ComboRule(ID, null, items))
                .withMessage("comboPrice");
        assertThatNullPointerException().isThrownBy(() -> new ComboRule(ID, cop("5000"), null))
                .withMessage("items");
        assertThatNullPointerException().isThrownBy(() -> new Item(null, 1)).withMessage("sku");
    }
}
