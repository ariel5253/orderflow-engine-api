package com.orderflow.engine.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The money rules of a country: its ISO currency code, the decimal scale of that currency and the
 * rounding mode of the country. The values come from country data, never from
 * {@code java.util.Currency}, which reports COP with scale 2.
 */
public record CurrencyPolicy(String currencyCode, int scale, RoundingMode roundingMode) {

    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");
    private static final int MAX_SCALE = 4;

    public CurrencyPolicy {
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(roundingMode, "roundingMode");
        if (!ISO_CODE.matcher(currencyCode).matches()) {
            throw new IllegalArgumentException(
                    "Currency code must be three uppercase letters: '" + currencyCode + "'");
        }
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "Currency scale must be between 0 and " + MAX_SCALE + ": " + scale);
        }
        if (roundingMode == RoundingMode.UNNECESSARY) {
            throw new IllegalArgumentException("Rounding mode UNNECESSARY cannot round an amount");
        }
    }

    /**
     * Rounds an exact amount to the currency scale with the country rounding mode. This is the only
     * place where an amount is rounded (rounding points RP-2 and RP-4).
     */
    public BigDecimal round(BigDecimal exact) {
        return exact.setScale(scale, roundingMode);
    }
}
