package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TierRuleTest {

    private static final UUID ID = UUID.fromString("01a13cdb-cc00-7001-8003-000000000001");
    private static final BigDecimal PRICE = new BigDecimal("2500");

    private static Bracket bracket(long from, String percent) {
        return new Bracket(from, new BigDecimal(percent));
    }

    private static TierRule rule(Bracket... brackets) {
        return new TierRule(ID, Scope.SKU, "BEV-COLA-350", List.of(brackets));
    }

    @Test
    void sortsItsBracketsByTheirFirstUnit() {
        TierRule rule = rule(bracket(20, "0.100000"), bracket(1, "0.000000"), bracket(10, "0.050000"));

        assertThat(rule.brackets()).extracting(Bracket::from).containsExactly(1L, 10L, 20L);
        // 10 x 2,500 x 5% + 6 x 2,500 x 10% = 2,750, as with the brackets given in order.
        assertThat(rule.exactDiscount(1, 25, PRICE)).isEqualByComparingTo("2750");
    }

    @Test
    void keepsItsBracketsWhateverHappensToTheGivenList() {
        List<Bracket> brackets = new ArrayList<>(List.of(bracket(10, "0.050000")));

        TierRule rule = new TierRule(ID, Scope.SKU, "BEV-COLA-350", brackets);
        brackets.add(bracket(20, "0.100000"));

        assertThat(rule.brackets()).containsExactly(bracket(10, "0.050000"));
    }

    @Test
    void discountsTheUnitsOfARangeThatStartsInsideABracket() {
        // Units 15 to 25: 15 to 19 are 5 units at 5% (625) and 20 to 25 are 6 units at 10% (1,500).
        TierRule rule = rule(bracket(1, "0.000000"), bracket(10, "0.050000"), bracket(20, "0.100000"));

        assertThat(rule.exactDiscount(15, 25, PRICE)).isEqualByComparingTo("2125");
    }

    @Test
    void keepsTheDiscountExact() {
        // 2 x 2,505 x 5% + 2,505 x 10% = 250.5 + 250.5. The rule never rounds: the step does.
        TierRule rule = rule(bracket(3, "0.050000"), bracket(5, "0.100000"));

        assertThat(rule.exactDiscount(1, 5, new BigDecimal("2505"))).isEqualByComparingTo("501.0");
        assertThat(rule.exactDiscount(3, 3, new BigDecimal("2505"))).isEqualByComparingTo("125.25");
    }

    @Test
    void anEmptyRangeHasNoDiscount() {
        TierRule rule = rule(bracket(1, "0.100000"));

        assertThat(rule.exactDiscount(13, 12, PRICE)).isZero();
    }

    @Test
    void rejectsARuleWithoutBrackets() {
        assertThatIllegalArgumentException().isThrownBy(() -> rule())
                .withMessageContaining(ID.toString());
    }

    @Test
    void rejectsTwoBracketsThatStartAtTheSameUnit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule(bracket(10, "0.050000"), bracket(10, "0.100000")))
                .withMessageContaining(ID.toString())
                .withMessageContaining("unit 10");
    }

    @Test
    void acceptsABracketFromUnitOneAndRejectsOneBefore() {
        assertThat(bracket(1, "0.050000").from()).isEqualTo(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> bracket(0, "0.050000"))
                .withMessageEndingWith(": 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000000", "0.050000", "1", "1.000000"})
    void acceptsAPercentFromZeroToOne(String percent) {
        assertThat(bracket(1, percent).percent()).isEqualByComparingTo(percent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.000001", "1.000001", "5"})
    void rejectsAPercentOutsideZeroToOne(String percent) {
        assertThatIllegalArgumentException().isThrownBy(() -> bracket(1, percent))
                .withMessageContaining(percent);
    }

    @Test
    void rejectsMissingValues() {
        List<Bracket> brackets = List.of(bracket(10, "0.050000"));

        assertThatNullPointerException().isThrownBy(() -> new TierRule(null, Scope.SKU, "BEV-COLA-350", brackets))
                .withMessage("id");
        assertThatNullPointerException().isThrownBy(() -> new TierRule(ID, null, "BEV-COLA-350", brackets))
                .withMessage("scope");
        assertThatNullPointerException().isThrownBy(() -> new TierRule(ID, Scope.SKU, null, brackets))
                .withMessage("target");
        assertThatNullPointerException().isThrownBy(() -> new TierRule(ID, Scope.SKU, "BEV-COLA-350", null))
                .withMessage("brackets");
        assertThatNullPointerException().isThrownBy(() -> new Bracket(1, null)).withMessage("percent");
    }
}
