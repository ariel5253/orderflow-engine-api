package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * The rules of the tenant's country that a quote follows: the currency policy that prices and rounds it, and how long
 * it stays open. Both are country data (P-07), read when the quote is created.
 */
public record TenantPolicy(CurrencyPolicy currency, Duration quoteTtl) {

    public TenantPolicy {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(quoteTtl, "quoteTtl");
    }
}
