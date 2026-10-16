package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * The rules of the tenant's country that a quote follows: the currency policy that prices and rounds it, how the
 * country taxes gift lines (BR-04), and how long the quote stays open. All three are country data (P-07), read when
 * the quote is created.
 */
public record TenantPolicy(CurrencyPolicy currency, GiftTaxPolicy giftTaxPolicy, Duration quoteTtl) {

    public TenantPolicy {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(giftTaxPolicy, "giftTaxPolicy");
        Objects.requireNonNull(quoteTtl, "quoteTtl");
    }
}
