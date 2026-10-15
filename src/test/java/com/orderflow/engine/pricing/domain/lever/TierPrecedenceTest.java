package com.orderflow.engine.pricing.domain.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.Breakdown;
import com.orderflow.engine.pricing.domain.Cart;
import com.orderflow.engine.pricing.domain.CartLine;
import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Discount;
import com.orderflow.engine.pricing.domain.LineBreakdown;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingEngine;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-15 and SPEC BR-02 (A-02): a SKU that has its own tier rule is removed from the rule of its
 * family. It gets only its own rule, and its units are not counted toward the family thresholds.
 */
class TierPrecedenceTest {

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static final UUID FAMILY_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000001");
    private static final UUID SKU_RULE = UUID.fromString("01a13cdb-cc00-7002-8003-000000000002");

    private static final List<Product> CATALOG = List.of(
            new Product("BEV-COLA-350", "colas", usd("0.60"), new BigDecimal("0.150000")),
            new Product("BEV-COLA-1500", "colas", usd("1.45"), new BigDecimal("0.150000")));

    // Units 12 to 23 of the colas get 4%, unit 24 on gets 8%.
    private static final TierRule COLAS = new TierRule(FAMILY_RULE, Scope.FAMILY, "colas", List.of(
            new Bracket(12, new BigDecimal("0.040000")),
            new Bracket(24, new BigDecimal("0.080000"))));

    private final PricingEngine engine = new PricingEngine();

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    private static Discount tier(UUID rule, String amount) {
        return new Discount(Discount.Kind.TIER, rule, usd(amount));
    }

    private static TierRule colas1500From(long from) {
        return new TierRule(SKU_RULE, Scope.SKU, "BEV-COLA-1500",
                List.of(new Bracket(from, new BigDecimal("0.100000"))));
    }

    private Breakdown price(List<TierRule> rules, CartLine... lines) {
        return engine.price(new Cart(List.of(lines)), new PricingSnapshot(USD, CATALOG, rules, List.of()));
    }

    @Test
    void aSkuRuleBeatsTheRuleOfItsFamily() {
        // Units 5 to 30 get the SKU rule's 10%: 26 x 1.45 x 10% = 3.77. The family rule would give
        // 12 x 1.45 x 4% + 7 x 1.45 x 8% = 1.508 -> 1.51.
        TierRule skuRule = colas1500From(5);

        Breakdown skuRuleFirst = price(List.of(skuRule, COLAS), new CartLine("BEV-COLA-1500", 30));
        Breakdown familyRuleFirst = price(List.of(COLAS, skuRule), new CartLine("BEV-COLA-1500", 30));

        assertThat(skuRuleFirst.lines()).extracting(LineBreakdown::discounts)
                .containsExactly(List.of(tier(SKU_RULE, "3.77")));
        assertThat(skuRuleFirst.totals().discounts()).containsExactly(tier(SKU_RULE, "3.77"));
        assertThat(familyRuleFirst).isEqualTo(skuRuleFirst);
    }

    @Test
    void theUnitsOfASkuWithItsOwnRuleAreNotCountedTowardTheFamilyThresholds() {
        // BEV-COLA-1500 sorts first. Without it the family count starts at the colas 350, which take
        // units 1 to 12: one unit at 4% gives 0.60 x 4% = 0.024 -> 0.02. Counted after the 20 colas
        // 1500, they would take units 21 to 32: 3 x 0.60 x 4% + 9 x 0.60 x 8% = 0.504 -> 0.50.
        // The colas 1500 get their own rule: units 5 to 20 give 16 x 1.45 x 10% = 2.32.
        Breakdown breakdown = price(List.of(COLAS, colas1500From(5)),
                new CartLine("BEV-COLA-1500", 20),
                new CartLine("BEV-COLA-350", 12));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(tier(SKU_RULE, "2.32"))),
                        tuple("BEV-COLA-350", List.of(tier(FAMILY_RULE, "0.02"))));
    }

    @Test
    void aSkuRuleThatGivesNothingStillKeepsItsSkuOutOfTheFamilyRule() {
        // 30 colas 1500 do not reach the SKU rule's first bracket at unit 50, and the family rule,
        // which would give 1.51, does not apply to them.
        Breakdown breakdown = price(List.of(COLAS, colas1500From(50)), new CartLine("BEV-COLA-1500", 30));

        assertThat(breakdown.lines()).extracting(LineBreakdown::discounts).containsExactly(List.of());
        assertThat(breakdown.totals().discounts()).isEmpty();
    }

    @Test
    void theFamilyRuleAppliesWhenNoSkuOfTheFamilyHasItsOwnRule() {
        // The family counts both lines: the colas 1500 take units 1 to 20 and the colas 350 units 21
        // to 32. Colas 1500: 9 x 1.45 x 4% = 0.522 -> 0.52. Colas 350: 0.504 -> 0.50, as above.
        Breakdown breakdown = price(List.of(COLAS),
                new CartLine("BEV-COLA-1500", 20),
                new CartLine("BEV-COLA-350", 12));

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::discounts)
                .containsExactly(
                        tuple("BEV-COLA-1500", List.of(tier(FAMILY_RULE, "0.52"))),
                        tuple("BEV-COLA-350", List.of(tier(FAMILY_RULE, "0.50"))));
        assertThat(breakdown.totals().discounts()).containsExactly(tier(FAMILY_RULE, "1.02"));
    }
}
