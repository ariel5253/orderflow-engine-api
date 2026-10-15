package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import java.math.BigDecimal;

/**
 * Reads money columns. They are numeric(19,4) for every currency, so a value is read back at the scale of its
 * currency, and a value with more decimals than that scale is rejected (data model DM-01): no database CHECK can know
 * the currency of the row without a trigger, so the check happens here, on every read.
 */
final class MoneyColumns {

    private MoneyColumns() {
    }

    /** The stored value as money of this currency. {@code what} names the value in the error of a rejected one. */
    static Money read(BigDecimal stored, CurrencyPolicy currency, String what) {
        BigDecimal amount = stored.stripTrailingZeros();
        if (amount.scale() > currency.scale()) {
            throw new IllegalStateException(what + " " + stored.toPlainString() + " has more decimals than the "
                    + currency.currencyCode() + " scale " + currency.scale());
        }
        return new Money(amount, currency);
    }
}
