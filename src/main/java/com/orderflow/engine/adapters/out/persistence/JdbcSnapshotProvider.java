package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
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
import com.orderflow.engine.quoting.SnapshotProvider;
import com.orderflow.engine.quoting.TenantPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the pricing snapshot of a quote with explicit SQL: the policy of the tenant's country, the products the cart
 * names, and the tenant's active tier, combo and gift rules, each gift rule with the product it gives. Scale and
 * rounding come from the currency and country rows, never from {@code java.util.Currency} (SPEC §4). Rules are
 * deactivated rather than deleted, and every lever query reads active rules only, so a deactivated lever reaches no
 * new quote while a sealed quote keeps the components it was priced with.
 */
@Repository
public class JdbcSnapshotProvider implements SnapshotProvider {

    /** The quote TTL is an interval, read as whole microseconds, the precision of a PostgreSQL interval. */
    private static final String TENANT_POLICY = """
            SELECT c.currency_code, cur.scale, c.rounding_mode, c.gift_tax_policy,
                   CAST(EXTRACT(EPOCH FROM c.quote_ttl) * 1000000 AS bigint) AS quote_ttl_micros
            FROM tenants t
            JOIN countries c ON c.code = t.country_code
            JOIN currencies cur ON cur.code = c.currency_code
            WHERE t.id = :tenantId
            """;

    private static final String CUSTOMER_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM customers WHERE tenant_id = :tenantId AND id = :customerId)
            """;

    /**
     * The products of the cart. The SKUs are bound as one array, so the statement keeps a fixed number of parameters
     * whatever the size of the cart. PostgreSQL refuses a statement with more than 65,535 parameters, which an IN list
     * of one parameter per SKU would reach.
     */
    private static final String PRODUCTS = """
            SELECT sku, family_code, list_price, tax_rate
            FROM products
            WHERE tenant_id = :tenantId AND sku = ANY(:skus)
            """;

    /**
     * One row per bracket of an active tier rule. A rule targets a product, read as its SKU, or a family (BR-02). A
     * rule with no bracket would discount nothing, so the join leaves it out.
     */
    private static final String TIER_RULES = """
            SELECT r.id, p.sku, r.family_code, b.from_qty, b.percent
            FROM tier_rules r
            JOIN tier_brackets b ON b.tenant_id = r.tenant_id AND b.tier_rule_id = r.id
            LEFT JOIN products p ON p.tenant_id = r.tenant_id AND p.id = r.product_id
            WHERE r.tenant_id = :tenantId AND r.active
            ORDER BY r.id, b.from_qty
            """;

    /**
     * One row per item of an active combo, in combo-id order, the order BR-03 evaluates combos in. A combo with no
     * item could never apply, so the join leaves it out.
     */
    private static final String COMBO_RULES = """
            SELECT c.id, c.combo_price, p.sku, i.min_qty
            FROM combo_rules c
            JOIN combo_items i ON i.tenant_id = c.tenant_id AND i.combo_rule_id = c.id
            JOIN products p ON p.tenant_id = i.tenant_id AND p.id = i.product_id
            WHERE c.tenant_id = :tenantId AND c.active
            ORDER BY c.id, p.sku
            """;

    /**
     * The active gift rules, each with the product it gives, because the engine prices a gift line at that product's
     * list price and tax rate. The product is read in the same statement as its rule: each statement of a READ
     * COMMITTED transaction sees the rows committed when it starts, so a product read by another statement could miss
     * a rule activated in between. The table allows exactly one trigger per rule: a product, read as its SKU, or a
     * family, each with a minimum quantity, or a minimum gross subtotal alone (BR-04).
     */
    private static final String GIFT_RULES = """
            SELECT g.id, t.sku AS trigger_sku, g.trigger_family_code, g.min_qty, g.min_gross,
                   p.sku AS gift_sku, p.family_code AS gift_family_code, p.list_price AS gift_list_price,
                   p.tax_rate AS gift_tax_rate, g.gift_qty, g.repeatable
            FROM gift_rules g
            LEFT JOIN products t ON t.tenant_id = g.tenant_id AND t.id = g.trigger_product_id
            JOIN products p ON p.tenant_id = g.tenant_id AND p.id = g.gift_product_id
            WHERE g.tenant_id = :tenantId AND g.active
            ORDER BY g.id
            """;

    private final JdbcClient jdbc;

    public JdbcSnapshotProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantPolicy> tenantPolicy(String tenantId) {
        return jdbc.sql(TENANT_POLICY)
                .param("tenantId", tenantId)
                .query((rs, row) -> new TenantPolicy(
                        new CurrencyPolicy(rs.getString("currency_code"), rs.getInt("scale"),
                                RoundingMode.valueOf(rs.getString("rounding_mode"))),
                        GiftTaxPolicy.valueOf(rs.getString("gift_tax_policy")),
                        Duration.of(rs.getLong("quote_ttl_micros"), ChronoUnit.MICROS)))
                .optional();
    }

    @Override
    public boolean customerExists(String tenantId, UUID customerId) {
        return jdbc.sql(CUSTOMER_EXISTS)
                .param("tenantId", tenantId)
                .param("customerId", customerId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public PricingSnapshot pricingSnapshot(String tenantId, TenantPolicy policy, Set<String> skus) {
        CurrencyPolicy currency = policy.currency();
        Map<String, Product> products = new LinkedHashMap<>();
        products(tenantId, currency, skus).forEach(product -> products.put(product.sku(), product));
        List<TierRule> tierRules = tierRules(tenantId);
        List<ComboRule> comboRules = comboRules(tenantId, currency);
        List<GiftRuleRow> giftRules = giftRules(tenantId, currency);
        // Each gift product is added once, and a gift SKU the cart also holds keeps the cart's row.
        giftRules.forEach(gift -> products.putIfAbsent(gift.product().sku(), gift.product()));
        return new PricingSnapshot(currency, policy.giftTaxPolicy(), List.copyOf(products.values()), tierRules,
                comboRules, giftRules.stream().map(GiftRuleRow::rule).toList());
    }

    private List<Product> products(String tenantId, CurrencyPolicy currency, Set<String> skus) {
        return jdbc.sql(PRODUCTS)
                .param("tenantId", tenantId)
                .param("skus", skus.toArray(String[]::new))
                .query((rs, row) -> product(tenantId, currency, rs.getString("sku"), rs.getString("family_code"),
                        rs.getBigDecimal("list_price"), rs.getBigDecimal("tax_rate")))
                .list();
    }

    private List<TierRule> tierRules(String tenantId) {
        List<TierBracketRow> rows = jdbc.sql(TIER_RULES)
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    String sku = rs.getString("sku");
                    return new TierBracketRow(rs.getObject("id", UUID.class),
                            sku != null ? Scope.SKU : Scope.FAMILY,
                            sku != null ? sku : rs.getString("family_code"),
                            new Bracket(rs.getLong("from_qty"), rs.getBigDecimal("percent")));
                })
                .list();
        return grouped(rows, TierBracketRow::ruleId).stream()
                .map(brackets -> {
                    TierBracketRow rule = brackets.getFirst();
                    return new TierRule(rule.ruleId(), rule.scope(), rule.target(),
                            brackets.stream().map(TierBracketRow::bracket).toList());
                })
                .toList();
    }

    private List<ComboRule> comboRules(String tenantId, CurrencyPolicy currency) {
        List<ComboItemRow> rows = jdbc.sql(COMBO_RULES)
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    UUID ruleId = rs.getObject("id", UUID.class);
                    return new ComboItemRow(ruleId,
                            MoneyColumns.read(rs.getBigDecimal("combo_price"), currency,
                                    "Combo price of combo rule " + ruleId + " of tenant '" + tenantId + "'"),
                            new Item(rs.getString("sku"), rs.getLong("min_qty")));
                })
                .list();
        return grouped(rows, ComboItemRow::ruleId).stream()
                .map(items -> {
                    ComboItemRow rule = items.getFirst();
                    return new ComboRule(rule.ruleId(), rule.comboPrice(),
                            items.stream().map(ComboItemRow::item).toList());
                })
                .toList();
    }

    private List<GiftRuleRow> giftRules(String tenantId, CurrencyPolicy currency) {
        return jdbc.sql(GIFT_RULES)
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    UUID ruleId = rs.getObject("id", UUID.class);
                    String minGross = "Minimum gross subtotal of gift rule " + ruleId + " of tenant '" + tenantId + "'";
                    String giftSku = rs.getString("gift_sku");
                    return new GiftRuleRow(
                            new GiftRule(ruleId, trigger(rs, currency, minGross), giftSku, rs.getLong("gift_qty"),
                                    rs.getBoolean("repeatable")),
                            product(tenantId, currency, giftSku, rs.getString("gift_family_code"),
                                    rs.getBigDecimal("gift_list_price"), rs.getBigDecimal("gift_tax_rate")));
                })
                .list();
    }

    /** A product, with its list price read at the scale of the tenant's currency. */
    private static Product product(String tenantId, CurrencyPolicy currency, String sku, String familyCode,
            BigDecimal listPrice, BigDecimal taxRate) {
        return new Product(sku, familyCode, MoneyColumns.read(listPrice, currency,
                "List price of SKU '" + sku + "' of tenant '" + tenantId + "'"), taxRate);
    }

    /** The trigger of a gift rule row. {@code minGross} names its gross subtotal in the error of a rejected one. */
    private static Trigger trigger(ResultSet rs, CurrencyPolicy currency, String minGross) throws SQLException {
        String sku = rs.getString("trigger_sku");
        if (sku != null) {
            return new SkuQuantity(sku, rs.getLong("min_qty"));
        }
        String family = rs.getString("trigger_family_code");
        if (family != null) {
            return new FamilyQuantity(family, rs.getLong("min_qty"));
        }
        return new GrossSubtotal(MoneyColumns.read(rs.getBigDecimal("min_gross"), currency, minGross));
    }

    /** The rows of each rule together, in the order the rules first appear. */
    private static <T> List<List<T>> grouped(List<T> rows, Function<T, UUID> ruleId) {
        return List.copyOf(rows.stream()
                .collect(Collectors.groupingBy(ruleId, LinkedHashMap::new, Collectors.toList()))
                .values());
    }

    private record TierBracketRow(UUID ruleId, Scope scope, String target, Bracket bracket) {
    }

    private record ComboItemRow(UUID ruleId, Money comboPrice, Item item) {
    }

    private record GiftRuleRow(GiftRule rule, Product product) {
    }
}
