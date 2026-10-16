package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.SealedQuote.Adjustment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Seeded ids and sealed quotes shared by the quote tests. The ids are those of the seed changelog. */
public final class QuoteTestData {

    public static final String CO = "co-alpha";
    public static final String EC = "ec-alpha";

    /** The co-alpha customer with a 500,000 COP credit account and nothing consumed. */
    public static final UUID CO_CREDIT_CUSTOMER = UUID.fromString("01a13cdb-cc00-7001-8001-000000000001");
    /** The co-alpha customer that pays cash only: it has no credit account. */
    public static final UUID CO_CASH_CUSTOMER = UUID.fromString("01a13cdb-cc00-7001-8001-000000000002");
    /** The ec-alpha customer with a 1,500.00 USD credit account. */
    public static final UUID EC_CREDIT_CUSTOMER = UUID.fromString("01a13cdb-cc00-7002-8001-000000000001");
    /** The ec-alpha customer that pays cash only. */
    public static final UUID EC_CASH_CUSTOMER = UUID.fromString("01a13cdb-cc00-7002-8001-000000000002");

    public static final UUID CO_TIER_RULE = UUID.fromString("01a13cdb-cc00-7001-8003-000000000001");
    public static final UUID CO_COMBO_RULE = UUID.fromString("01a13cdb-cc00-7001-8004-000000000001");
    public static final UUID CO_GIFT_RULE = UUID.fromString("01a13cdb-cc00-7001-8005-000000000001");

    public static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);

    private QuoteTestData() {
    }

    public static Money cop(String amount) {
        return new Money(new BigDecimal(amount), COP);
    }

    /**
     * A co-alpha cash quote with every kind of line value: 30 colas and 10 chips share ten applications of the combo,
     * the ten colas the combo leaves get a tier discount, and the ten chips earn a taxed gift water. Its amounts
     * satisfy the BR-05 identities: the total is 74,875 of net plus 12,659 of taxes, 87,534.
     */
    public static SealedQuote withEveryComponent(UUID customer, Instant createdAt, Instant expiresAt) {
        return new SealedQuote(CO, customer, PaymentMethod.CASH, COP, createdAt, expiresAt, List.of(
                new SealedQuote.Line("BEV-COLA-350", null, 30, cop("2500"), new BigDecimal("0.190000"),
                        cop("61640"), cop("11712"), List.of(
                                new Adjustment(Adjustment.Kind.COMBO, CO_COMBO_RULE, cop("13235")),
                                new Adjustment(Adjustment.Kind.TIER, CO_TIER_RULE, cop("125")))),
                new SealedQuote.Line("SNK-CHIPS-45", null, 10, cop("1800"), new BigDecimal("0.050000"),
                        cop("13235"), cop("662"), List.of(
                                new Adjustment(Adjustment.Kind.COMBO, CO_COMBO_RULE, cop("4765")))),
                new SealedQuote.Line("BEV-WATER-600", CO_GIFT_RULE, 1, cop("1500"), new BigDecimal("0.190000"),
                        cop("1500"), cop("285"), List.of())));
    }

    /** A co-alpha quote of three 1.5 l colas at 5,200 with 19% tax: 15,600 plus 2,964 of tax. */
    public static SealedQuote threeColas(UUID customer, PaymentMethod paymentMethod, Instant createdAt,
            Instant expiresAt) {
        return new SealedQuote(CO, customer, paymentMethod, COP, createdAt, expiresAt, List.of(
                new SealedQuote.Line("BEV-COLA-1500", null, 3, cop("5200"), new BigDecimal("0.190000"),
                        cop("15600"), cop("2964"), List.of())));
    }
}
