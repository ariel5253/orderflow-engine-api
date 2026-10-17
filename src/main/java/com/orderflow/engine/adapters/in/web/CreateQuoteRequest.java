package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.pricing.domain.Cart;
import com.orderflow.engine.pricing.domain.CartLine;
import com.orderflow.engine.quoting.PaymentMethod;
import java.math.BigDecimal;
import java.util.List;

/**
 * The body of {@code POST /api/v1/quotes} (api-contract E-01). A missing or null field makes the body malformed. A
 * quantity is read as a decimal, so one that is not a whole number is reported as INVALID_QUANTITY instead of being
 * truncated or rejected as an unreadable body, and so is one too large for a long, which is above the bound of SPEC
 * BR-00 as well. Quantities below one or above that bound are left to the cart normalization, which rejects every
 * invalid cart in one place.
 */
public record CreateQuoteRequest(PaymentMethod paymentMethod, List<Line> lines) {

    public record Line(String sku, BigDecimal qty) {
    }

    /** Whether every required field is present. */
    boolean isComplete() {
        return paymentMethod != null && lines != null
                && lines.stream().allMatch(line -> line != null && line.sku() != null && line.qty() != null);
    }

    /** The cart as the client sent it, in its order and with its repeated SKUs. Call only on a complete body. */
    Cart toCart() {
        return new Cart(lines.stream().map(line -> new CartLine(line.sku(), wholeQuantity(line.qty()))).toList());
    }

    private static long wholeQuantity(BigDecimal qty) {
        try {
            return qty.longValueExact();
        } catch (ArithmeticException notAWholeNumberOrTooLarge) {
            throw new ApiException(ErrorCode.INVALID_QUANTITY);
        }
    }
}
