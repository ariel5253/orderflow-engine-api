package com.orderflow.engine.pricing.domain;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.GiftRule;
import com.orderflow.engine.pricing.domain.lever.GiftRule.FamilyQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.GrossSubtotal;
import com.orderflow.engine.pricing.domain.lever.GiftRule.SkuQuantity;
import com.orderflow.engine.pricing.domain.lever.GiftRule.Trigger;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.pricing.domain.lever.TierRule;
import com.orderflow.engine.pricing.domain.lever.TierRule.Bracket;
import com.orderflow.engine.pricing.domain.lever.TierRule.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Reads the inputs of a golden case (SPEC section 11): the cart of {@code request.json}, and the pricing snapshot of
 * {@code snapshot.json}, which holds the country policy, the catalog and the active levers the engine reads. The
 * engine's golden tests and the quote's golden tests read their fixtures the same way.
 */
public final class GoldenFixtures {

    private GoldenFixtures() {
    }

    /** The cart of a quote request, with its lines as they were sent. */
    public static Cart cart(JsonNode request) {
        List<CartLine> lines = new ArrayList<>();
        for (JsonNode line : request.required("lines")) {
            lines.add(new CartLine(
                    line.required("sku").stringValue(),
                    line.required("qty").longValue()));
        }
        return new Cart(lines);
    }

    /** The pricing snapshot: the country policy, the catalog and the active tier, combo and gift rules. */
    public static PricingSnapshot snapshot(JsonNode snapshot) {
        JsonNode policyNode = snapshot.required("policy");
        CurrencyPolicy policy = new CurrencyPolicy(
                policyNode.required("currency").stringValue(),
                policyNode.required("scale").intValue(),
                RoundingMode.valueOf(policyNode.required("roundingMode").stringValue()));
        List<Product> products = new ArrayList<>();
        for (JsonNode product : snapshot.required("products")) {
            products.add(new Product(
                    product.required("sku").stringValue(),
                    product.required("family").stringValue(),
                    new Money(new BigDecimal(product.required("listPrice").stringValue()), policy),
                    new BigDecimal(product.required("taxRate").stringValue())));
        }
        return new PricingSnapshot(policy,
                GiftTaxPolicy.valueOf(policyNode.required("giftTaxPolicy").stringValue()),
                products,
                tierRules(snapshot.required("tierRules")),
                comboRules(snapshot.required("comboRules"), policy),
                giftRules(snapshot.required("giftRules"), policy));
    }

    private static List<TierRule> tierRules(JsonNode rules) {
        List<TierRule> tierRules = new ArrayList<>();
        for (JsonNode rule : rules) {
            List<Bracket> brackets = new ArrayList<>();
            for (JsonNode bracket : rule.required("brackets")) {
                brackets.add(new Bracket(
                        bracket.required("from").longValue(),
                        new BigDecimal(bracket.required("percent").stringValue())));
            }
            tierRules.add(new TierRule(
                    UUID.fromString(rule.required("id").stringValue()),
                    Scope.valueOf(rule.required("scope").stringValue()),
                    rule.required("target").stringValue(),
                    brackets));
        }
        return tierRules;
    }

    private static List<ComboRule> comboRules(JsonNode rules, CurrencyPolicy policy) {
        List<ComboRule> comboRules = new ArrayList<>();
        for (JsonNode rule : rules) {
            List<Item> items = new ArrayList<>();
            for (JsonNode item : rule.required("items")) {
                items.add(new Item(item.required("sku").stringValue(), item.required("minQty").longValue()));
            }
            comboRules.add(new ComboRule(
                    UUID.fromString(rule.required("id").stringValue()),
                    new Money(new BigDecimal(rule.required("comboPrice").stringValue()), policy),
                    items));
        }
        return comboRules;
    }

    private static List<GiftRule> giftRules(JsonNode rules, CurrencyPolicy policy) {
        List<GiftRule> giftRules = new ArrayList<>();
        for (JsonNode rule : rules) {
            giftRules.add(new GiftRule(
                    UUID.fromString(rule.required("id").stringValue()),
                    trigger(rule.required("trigger"), policy),
                    rule.required("giftSku").stringValue(),
                    rule.required("giftQty").longValue(),
                    rule.required("repeatable").booleanValue()));
        }
        return giftRules;
    }

    // A trigger names a SKU or a family with a minimum quantity, or a minimum gross alone, like a
    // row of the gift_rules table.
    private static Trigger trigger(JsonNode trigger, CurrencyPolicy policy) {
        if (trigger.has("sku")) {
            return new SkuQuantity(trigger.required("sku").stringValue(), trigger.required("minQty").longValue());
        }
        if (trigger.has("family")) {
            return new FamilyQuantity(
                    trigger.required("family").stringValue(), trigger.required("minQty").longValue());
        }
        return new GrossSubtotal(new Money(new BigDecimal(trigger.required("minGross").stringValue()), policy));
    }
}
