package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.SealedQuote;
import com.orderflow.engine.quoting.SealedQuote.Adjustment;
import com.orderflow.engine.quoting.StoredQuote;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sealed quotes with explicit SQL. There is no UPDATE statement here: a quote, its lines and their discount
 * components are only ever inserted and read (data model DM-02). Every read is filtered by tenant and customer, so a
 * quote of anyone else is never found (BD-01).
 */
@Repository
public class JdbcQuoteRepository implements QuoteRepository {

    private static final String INSERT_QUOTE = """
            INSERT INTO quotes (tenant_id, id, customer_id, currency_code, payment_method, created_at, expires_at)
            VALUES (:tenantId, :quoteId, :customerId, :currency, :paymentMethod, :createdAt, :expiresAt)
            """;

    /**
     * The product is found by its SKU in the tenant's catalog. A SKU missing from it leaves product_id null, which the
     * column rejects, so a line can never be stored without its product.
     */
    private static final String INSERT_LINE = """
            INSERT INTO quote_lines
                (tenant_id, quote_id, line_no, product_id, gift_rule_id, qty, unit_price, tax_rate, taxable_base, tax)
            VALUES (:tenantId, :quoteId, :lineNo,
                    (SELECT id FROM products WHERE tenant_id = :tenantId AND sku = :sku),
                    :giftRuleId, :qty, :unitPrice, :taxRate, :taxableBase, :tax)
            """;

    private static final String INSERT_ADJUSTMENT = """
            INSERT INTO quote_line_adjustments (tenant_id, quote_id, line_no, kind, rule_id, amount)
            VALUES (:tenantId, :quoteId, :lineNo, :kind, :ruleId, :amount)
            """;

    /**
     * The quote and its money rules: the scale of the currency it was quoted in, and the rounding mode of the tenant's
     * country, which reading never uses but every money value carries. The status is derived from whether an order
     * exists for the quote.
     */
    private static final String FIND_QUOTE = """
            SELECT q.payment_method, q.currency_code, cur.scale, c.rounding_mode, q.created_at, q.expires_at,
                   EXISTS (SELECT 1 FROM orders o WHERE o.tenant_id = q.tenant_id AND o.quote_id = q.id) AS confirmed
            FROM quotes q
            JOIN currencies cur ON cur.code = q.currency_code
            JOIN tenants t ON t.id = q.tenant_id
            JOIN countries c ON c.code = t.country_code
            WHERE q.tenant_id = :tenantId AND q.id = :quoteId AND q.customer_id = :customerId
            """;

    private static final String FIND_LINES = """
            SELECT l.line_no, p.sku, p.name, l.gift_rule_id, l.qty, l.unit_price, l.tax_rate, l.taxable_base, l.tax
            FROM quote_lines l
            JOIN products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
            WHERE l.tenant_id = :tenantId AND l.quote_id = :quoteId
            ORDER BY l.line_no
            """;

    private static final String FIND_ADJUSTMENTS = """
            SELECT line_no, kind, rule_id, amount
            FROM quote_line_adjustments
            WHERE tenant_id = :tenantId AND quote_id = :quoteId
            ORDER BY line_no, kind, rule_id
            """;

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    public JdbcQuoteRepository(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public UUID insert(SealedQuote quote) {
        UUID quoteId = ids.next();
        jdbc.sql(INSERT_QUOTE)
                .param("tenantId", quote.tenantId())
                .param("quoteId", quoteId)
                .param("customerId", quote.customerId())
                .param("currency", quote.currency().currencyCode())
                .param("paymentMethod", quote.paymentMethod().name())
                .param("createdAt", timestamp(quote.createdAt()))
                .param("expiresAt", timestamp(quote.expiresAt()))
                .update();
        int lineNo = 0;
        for (SealedQuote.Line line : quote.lines()) {
            lineNo++;
            insertLine(quote.tenantId(), quoteId, lineNo, line);
        }
        return quoteId;
    }

    private void insertLine(String tenantId, UUID quoteId, int lineNo, SealedQuote.Line line) {
        jdbc.sql(INSERT_LINE)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .param("lineNo", lineNo)
                .param("sku", line.sku())
                .param("giftRuleId", line.giftRuleId())
                .param("qty", Math.toIntExact(line.qty()))
                .param("unitPrice", line.unitPrice().amount())
                .param("taxRate", line.taxRate())
                .param("taxableBase", line.taxableBase().amount())
                .param("tax", line.tax().amount())
                .update();
        for (Adjustment adjustment : line.adjustments()) {
            jdbc.sql(INSERT_ADJUSTMENT)
                    .param("tenantId", tenantId)
                    .param("quoteId", quoteId)
                    .param("lineNo", lineNo)
                    .param("kind", adjustment.kind().name())
                    .param("ruleId", adjustment.ruleId())
                    .param("amount", adjustment.amount().amount())
                    .update();
        }
    }

    @Override
    public Optional<StoredQuote> find(String tenantId, UUID customerId, UUID quoteId) {
        return jdbc.sql(FIND_QUOTE)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .param("customerId", customerId)
                .query((rs, row) -> header(rs))
                .optional()
                .map(header -> stored(tenantId, customerId, quoteId, header));
    }

    private StoredQuote stored(String tenantId, UUID customerId, UUID quoteId, Header header) {
        CurrencyPolicy currency = header.currency();
        Map<Integer, List<Adjustment>> adjustments = adjustments(tenantId, quoteId, currency);
        List<LineRow> rows = jdbc.sql(FIND_LINES)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((rs, row) -> {
                    int lineNo = rs.getInt("line_no");
                    String line = "Quote " + quoteId + " line " + lineNo;
                    return new LineRow(rs.getString("name"), new SealedQuote.Line(
                            rs.getString("sku"),
                            rs.getObject("gift_rule_id", UUID.class),
                            rs.getInt("qty"),
                            MoneyColumns.read(rs.getBigDecimal("unit_price"), currency, line + ": unit price"),
                            rs.getBigDecimal("tax_rate"),
                            MoneyColumns.read(rs.getBigDecimal("taxable_base"), currency, line + ": taxable base"),
                            MoneyColumns.read(rs.getBigDecimal("tax"), currency, line + ": tax"),
                            adjustments.getOrDefault(lineNo, List.of())));
                })
                .list();
        SealedQuote sealed = new SealedQuote(tenantId, customerId, header.paymentMethod(), currency,
                header.createdAt(), header.expiresAt(), rows.stream().map(LineRow::line).toList());
        // A gift line may quote the same product as a paid line, and both carry the same name.
        Map<String, String> descriptions = rows.stream()
                .collect(Collectors.toMap(row -> row.line().sku(), LineRow::description, (same, again) -> same));
        return new StoredQuote(quoteId, sealed, descriptions, header.confirmed());
    }

    private Map<Integer, List<Adjustment>> adjustments(String tenantId, UUID quoteId, CurrencyPolicy currency) {
        return jdbc.sql(FIND_ADJUSTMENTS)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((rs, row) -> {
                    int lineNo = rs.getInt("line_no");
                    return new LineAdjustment(lineNo, new Adjustment(
                            Adjustment.Kind.valueOf(rs.getString("kind")),
                            rs.getObject("rule_id", UUID.class),
                            MoneyColumns.read(rs.getBigDecimal("amount"), currency,
                                    "Quote " + quoteId + " line " + lineNo + ": adjustment")));
                })
                .list()
                .stream()
                .collect(Collectors.groupingBy(LineAdjustment::lineNo,
                        Collectors.mapping(LineAdjustment::adjustment, Collectors.toList())));
    }

    private static Header header(ResultSet rs) throws SQLException {
        return new Header(
                PaymentMethod.valueOf(rs.getString("payment_method")),
                new CurrencyPolicy(rs.getString("currency_code"), rs.getInt("scale"),
                        RoundingMode.valueOf(rs.getString("rounding_mode"))),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("confirmed"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Header(PaymentMethod paymentMethod, CurrencyPolicy currency, Instant createdAt,
            Instant expiresAt, boolean confirmed) {
    }

    private record LineRow(String description, SealedQuote.Line line) {
    }

    private record LineAdjustment(int lineNo, Adjustment adjustment) {
    }
}
