package com.orderflow.engine.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.CreditAdvice;
import com.orderflow.engine.quoting.QuoteStatus;
import com.orderflow.engine.quoting.QuoteView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The body of a quote (api-contract E-01 and E-02), in the breakdown shape of SPEC §8. Money is a JSON string at the
 * currency scale, never a JSON number, and the ISO currency code is given once for the whole quote. {@code credit} is
 * present only while the advisory credit check applies.
 */
public record QuoteResponse(
        UUID quoteId,
        QuoteStatus status,
        Instant expiresAt,
        String currency,
        List<Line> lines,
        Totals totals,
        @JsonInclude(JsonInclude.Include.NON_NULL) Credit credit) {

    public static QuoteResponse from(QuoteView quote) {
        return new QuoteResponse(quote.quoteId(), quote.status(), quote.expiresAt(), quote.currency(),
                quote.lines().stream().map(Line::from).toList(), Totals.from(quote.totals()),
                quote.credit() == null ? null : Credit.from(quote.credit()));
    }

    /** One line. {@code origin} and {@code referenceValue} appear only on a gift line. */
    public record Line(
            String sku,
            String description,
            long qty,
            @JsonProperty("isGift") boolean gift,
            @JsonInclude(JsonInclude.Include.NON_NULL) String origin,
            String unitPrice,
            String gross,
            List<Discount> discounts,
            List<ComboAllocation> comboAllocations,
            String net,
            @JsonInclude(JsonInclude.Include.NON_NULL) String referenceValue,
            String taxableBase,
            String taxRate,
            String tax) {

        static Line from(QuoteView.Line line) {
            return new Line(line.sku(), line.description(), line.qty(), line.gift(), line.origin(),
                    money(line.unitPrice()), money(line.gross()),
                    line.discounts().stream().map(Discount::from).toList(),
                    line.comboAllocations().stream().map(ComboAllocation::from).toList(),
                    money(line.net()), line.referenceValue() == null ? null : money(line.referenceValue()),
                    money(line.taxableBase()), line.taxRate().toPlainString(), money(line.tax()));
        }
    }

    public record Discount(String origin, String amount) {

        static Discount from(QuoteView.Discount discount) {
            return new Discount(discount.origin(), money(discount.amount()));
        }
    }

    public record ComboAllocation(UUID comboId, String amount) {

        static ComboAllocation from(QuoteView.ComboAllocation allocation) {
            return new ComboAllocation(allocation.comboId(), money(allocation.amount()));
        }
    }

    public record Gift(String sku, long qty, String origin) {

        static Gift from(QuoteView.Gift gift) {
            return new Gift(gift.sku(), gift.qty(), gift.origin());
        }
    }

    public record Totals(
            String grossSubtotal,
            List<Discount> discounts,
            List<Gift> gifts,
            String taxableBase,
            String taxes,
            String total) {

        static Totals from(QuoteView.Totals totals) {
            return new Totals(money(totals.grossSubtotal()),
                    totals.discounts().stream().map(Discount::from).toList(),
                    totals.gifts().stream().map(Gift::from).toList(),
                    money(totals.taxableBase()), money(totals.taxes()), money(totals.total()));
        }
    }

    public record Credit(boolean fits, String available, String shortfall) {

        static Credit from(CreditAdvice credit) {
            return new Credit(credit.fits(), money(credit.available()), money(credit.shortfall()));
        }
    }

    /** Money keeps the currency scale, so "2500" in COP and "0.60" in USD, written without an exponent. */
    private static String money(Money money) {
        return money.amount().toPlainString();
    }
}
