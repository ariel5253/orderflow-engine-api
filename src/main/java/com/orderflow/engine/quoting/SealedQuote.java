package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A priced cart as it is sealed: the quote, its lines and their discount components are written once and never
 * updated (SPEC §6, data model DM-02). Each line keeps the unit price and tax rate in force when it was quoted, and
 * the amounts that needed a rounding or a policy decision: its discount components, its taxable base and its tax.
 * Everything else in the breakdown is exact arithmetic on these facts, so it is derived when the quote is read
 * ({@link QuoteView}).
 */
public record SealedQuote(
        String tenantId,
        UUID customerId,
        PaymentMethod paymentMethod,
        CurrencyPolicy currency,
        Instant createdAt,
        Instant expiresAt,
        List<Line> lines) {

    public SealedQuote {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        lines = List.copyOf(lines);
    }

    /**
     * One quoted line, in the order the lines are numbered. A gift line carries the gift rule that produced it, and its
     * unit price is the list price that gives its reference value (BR-04). A paid line has no gift rule, so
     * {@code giftRuleId} is null.
     */
    public record Line(
            String sku,
            UUID giftRuleId,
            long qty,
            Money unitPrice,
            BigDecimal taxRate,
            Money taxableBase,
            Money tax,
            List<Adjustment> adjustments) {

        public Line {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(unitPrice, "unitPrice");
            Objects.requireNonNull(taxRate, "taxRate");
            Objects.requireNonNull(taxableBase, "taxableBase");
            Objects.requireNonNull(tax, "tax");
            adjustments = List.copyOf(adjustments);
        }

        public boolean isGift() {
            return giftRuleId != null;
        }
    }

    /**
     * One discount component of a line, already rounded (RP-2). The rule id records the rule that produced it, so the
     * component keeps its origin even after the rule is deactivated.
     */
    public record Adjustment(Kind kind, UUID ruleId, Money amount) {

        /** The lever that produced the component: a tier discount or a combo allocation. */
        public enum Kind {
            TIER,
            COMBO
        }

        public Adjustment {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(amount, "amount");
        }

        /** The origin as the API shows it, {@code TIER:<ruleId>} or {@code COMBO:<ruleId>}. */
        public String origin() {
            return kind + ":" + ruleId;
        }
    }
}
