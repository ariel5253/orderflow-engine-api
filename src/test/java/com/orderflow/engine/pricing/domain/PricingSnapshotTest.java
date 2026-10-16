package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.GiftRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PricingSnapshotTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static final UUID RULE_1 = UUID.fromString("01a13cdb-cc00-7001-8003-000000000001");
    private static final UUID RULE_2 = UUID.fromString("01a13cdb-cc00-7001-8003-000000000002");
    private static final UUID COMBO_1 = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");
    private static final UUID COMBO_2 = UUID.fromString("01a13cdb-cc00-7001-8004-000000000002");
    private static final UUID GIFT_1 = UUID.fromString("01a13cdb-cc00-7001-8005-000000000001");
    private static final UUID GIFT_2 = UUID.fromString("01a13cdb-cc00-7001-8005-000000000002");

    private static Product product(String sku, CurrencyPolicy policy, String listPrice, String taxRate) {
        return new Product(sku, "colas", new Money(new BigDecimal(listPrice), policy), new BigDecimal(taxRate));
    }

    // A snapshot of the COP policy without gift rules.
    private static PricingSnapshot snapshot(List<Product> products, List<TierRule> tierRules,
            List<ComboRule> comboRules) {
        return new PricingSnapshot(COP, GiftTaxPolicy.TAXED, products, tierRules, comboRules, List.of());
    }

    private static TierRule rule(UUID id, Scope scope, String target) {
        return new TierRule(id, scope, target, List.of(new Bracket(10, new BigDecimal("0.050000"))));
    }

    @Test
    void findsAProductByItsSkuCode() {
        Product cola = product("BEV-COLA-1500", COP, "5200", "0.190000");
        PricingSnapshot snapshot = snapshot(List.of(cola), List.of(), List.of());

        assertThat(snapshot.policy()).isEqualTo(COP);
        assertThat(snapshot.product("BEV-COLA-1500")).contains(cola);
        assertThat(snapshot.product("BEV-COLA-1500")).get().extracting(Product::family).isEqualTo("colas");
        assertThat(snapshot.product("BEV-TEA-500")).isEmpty();
    }

    @Test
    void rejectsAProductPricedWithAnotherPolicy() {
        List<Product> products = List.of(product("BEV-COLA-1500", USD, "1.45", "0.150000"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(products, List.of(), List.of()))
                .withMessageContaining("BEV-COLA-1500")
                .withMessageContaining("USD")
                .withMessageContaining("COP");
    }

    @Test
    void rejectsASkuListedTwice() {
        List<Product> products = List.of(
                product("BEV-COLA-1500", COP, "5200", "0.190000"),
                product("BEV-COLA-1500", COP, "5300", "0.190000"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(products, List.of(), List.of()))
                .withMessageContaining("BEV-COLA-1500");
    }

    @Test
    void keepsTheActiveTierRulesAndCannotBeChangedThroughTheGivenList() {
        TierRule skuRule = rule(RULE_1, Scope.SKU, "BEV-COLA-350");
        // A SKU rule and a family rule may share a code: they target different things.
        TierRule familyRule = rule(RULE_2, Scope.FAMILY, "BEV-COLA-350");
        List<TierRule> rules = new ArrayList<>(List.of(skuRule, familyRule));

        PricingSnapshot snapshot = snapshot(List.of(), rules, List.of());
        rules.clear();

        assertThat(snapshot.tierRules()).containsExactly(skuRule, familyRule);
    }

    @Test
    void rejectsTwoActiveTierRulesOnTheSameTarget() {
        List<TierRule> onOneSku = List.of(
                rule(RULE_1, Scope.SKU, "BEV-COLA-350"),
                rule(RULE_2, Scope.SKU, "BEV-COLA-350"));
        List<TierRule> onOneFamily = List.of(
                rule(RULE_1, Scope.FAMILY, "colas"),
                rule(RULE_2, Scope.FAMILY, "colas"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(List.of(), onOneSku, List.of()))
                .withMessageContaining("SKU BEV-COLA-350");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(List.of(), onOneFamily, List.of()))
                .withMessageContaining("FAMILY colas");
    }

    @Test
    void rejectsATierRuleListedTwice() {
        List<TierRule> rules = List.of(
                rule(RULE_1, Scope.SKU, "BEV-COLA-350"),
                rule(RULE_1, Scope.FAMILY, "colas"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(List.of(), rules, List.of()))
                .withMessageContaining(RULE_1.toString());
    }

    private static ComboRule combo(UUID id, CurrencyPolicy policy, String price) {
        return new ComboRule(id, new Money(new BigDecimal(price), policy), List.of(
                new Item("BEV-COLA-350", 2),
                new Item("SNK-CHIPS-45", 1)));
    }

    @Test
    void keepsTheActiveComboRulesAndCannotBeChangedThroughTheGivenList() {
        ComboRule first = combo(COMBO_1, COP, "5000");
        ComboRule second = combo(COMBO_2, COP, "6000");
        List<ComboRule> combos = new ArrayList<>(List.of(first, second));

        PricingSnapshot snapshot = snapshot(List.of(), List.of(), combos);
        combos.clear();

        assertThat(snapshot.comboRules()).containsExactly(first, second);
    }

    @Test
    void rejectsAComboRuleListedTwice() {
        List<ComboRule> combos = List.of(combo(COMBO_1, COP, "5000"), combo(COMBO_1, COP, "6000"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(List.of(), List.of(), combos))
                .withMessageContaining(COMBO_1.toString())
                .withMessageContaining("twice");
    }

    @Test
    void rejectsAComboPricedWithAnotherPolicy() {
        List<ComboRule> combos = List.of(combo(COMBO_1, USD, "3.99"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(List.of(), List.of(), combos))
                .withMessageContaining(COMBO_1.toString())
                .withMessageContaining("USD")
                .withMessageContaining("COP");
    }

    private static GiftRule gift(UUID id, GiftRule.Trigger trigger, String giftSku) {
        return new GiftRule(id, trigger, giftSku, 1, true);
    }

    private static PricingSnapshot withGifts(List<GiftRule> gifts) {
        return new PricingSnapshot(COP, GiftTaxPolicy.EXEMPT,
                List.of(product("BEV-WATER-600", COP, "1500", "0.190000")), List.of(), List.of(), gifts);
    }

    @Test
    void keepsTheGiftTaxPolicyAndTheActiveGiftRulesAndCannotBeChangedThroughTheGivenList() {
        GiftRule snacks = gift(GIFT_1, new FamilyQuantity("snacks", 10), "BEV-WATER-600");
        GiftRule gross = gift(GIFT_2, new GrossSubtotal(new Money(new BigDecimal("50000"), COP)), "BEV-WATER-600");
        List<GiftRule> gifts = new ArrayList<>(List.of(snacks, gross));

        PricingSnapshot snapshot = withGifts(gifts);
        gifts.clear();

        assertThat(snapshot.giftTaxPolicy()).isEqualTo(GiftTaxPolicy.EXEMPT);
        assertThat(snapshot.giftRules()).containsExactly(snacks, gross);
    }

    @Test
    void rejectsAGiftRuleListedTwice() {
        List<GiftRule> gifts = List.of(
                gift(GIFT_1, new SkuQuantity("SNK-CHIPS-45", 10), "BEV-WATER-600"),
                gift(GIFT_1, new FamilyQuantity("snacks", 20), "BEV-WATER-600"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withGifts(gifts))
                .withMessageContaining(GIFT_1.toString())
                .withMessageContaining("twice");
    }

    @Test
    void rejectsAGiftOfASkuTheTenantDoesNotSell() {
        List<GiftRule> gifts = List.of(gift(GIFT_1, new SkuQuantity("SNK-CHIPS-45", 10), "BEV-TEA-500"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withGifts(gifts))
                .withMessageContaining(GIFT_1.toString())
                .withMessageContaining("BEV-TEA-500");
    }

    @Test
    void rejectsAGrossSubtotalSetWithAnotherPolicy() {
        List<GiftRule> gifts = List.of(
                gift(GIFT_1, new GrossSubtotal(new Money(new BigDecimal("50.00"), USD)), "BEV-WATER-600"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withGifts(gifts))
                .withMessageContaining(GIFT_1.toString())
                .withMessageContaining("USD")
                .withMessageContaining("COP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000000", "0.190000", "1", "1.000000"})
    void acceptsATaxRateFromZeroToOne(String taxRate) {
        assertThat(product("BEV-COLA-1500", COP, "5200", taxRate).taxRate()).isEqualByComparingTo(taxRate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.000001", "1.000001", "19"})
    void rejectsATaxRateOutsideZeroToOne(String taxRate) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> product("BEV-COLA-1500", COP, "5200", taxRate))
                .withMessageContaining("BEV-COLA-1500")
                .withMessageContaining(taxRate);
    }

    @Test
    void rejectsMissingValues() {
        Money price = new Money(new BigDecimal("5200"), COP);
        BigDecimal rate = new BigDecimal("0.190000");

        assertThatNullPointerException().isThrownBy(() -> new Product(null, "colas", price, rate)).withMessage("sku");
        assertThatNullPointerException().isThrownBy(() -> new Product("BEV-COLA-1500", null, price, rate))
                .withMessage("family");
        assertThatNullPointerException().isThrownBy(() -> new Product("BEV-COLA-1500", "colas", null, rate))
                .withMessage("listPrice");
        assertThatNullPointerException().isThrownBy(() -> new Product("BEV-COLA-1500", "colas", price, null))
                .withMessage("taxRate");
        assertThatNullPointerException().isThrownBy(() -> new PricingSnapshot(null, GiftTaxPolicy.TAXED,
                        List.of(), List.of(), List.of(), List.of()))
                .withMessage("policy");
        assertThatNullPointerException().isThrownBy(() -> new PricingSnapshot(COP, null,
                        List.of(), List.of(), List.of(), List.of()))
                .withMessage("giftTaxPolicy");
    }
}
