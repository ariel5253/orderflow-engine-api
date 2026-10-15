package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.CartRejectedException.Code;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC BR-00 and AC-13: the engine prices only the normalized cart. */
class CartNormalizationTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);

    private static final PricingSnapshot CO_ALPHA = new PricingSnapshot(COP, GiftTaxPolicy.TAXED, List.of(
            product("BEV-COLA-1500", "colas", "5200", "0.190000"),
            product("BEV-WATER-600", "waters", "1500", "0.190000"),
            product("SNK-CHIPS-45", "snacks", "1800", "0.050000")), List.of(), List.of(), List.of());

    private final PricingEngine engine = new PricingEngine();

    private static Product product(String sku, String family, String listPrice, String taxRate) {
        return new Product(sku, family, new Money(new BigDecimal(listPrice), COP), new BigDecimal(taxRate));
    }

    private static Cart cart(CartLine... lines) {
        return new Cart(List.of(lines));
    }

    private static CartLine line(String sku, long qty) {
        return new CartLine(sku, qty);
    }

    @Test
    void mergesTheLinesOfARepeatedSkuByAddingTheirQuantities() {
        Cart normalized = CartNormalizer.normalize(cart(
                line("SNK-CHIPS-45", 2),
                line("BEV-WATER-600", 4),
                line("SNK-CHIPS-45", 1)));

        assertThat(normalized.lines()).containsExactly(
                line("BEV-WATER-600", 4),
                line("SNK-CHIPS-45", 3));
    }

    @Test
    void sortsTheLinesBySkuCode() {
        Cart normalized = CartNormalizer.normalize(cart(
                line("SNK-CHIPS-45", 1),
                line("BEV-WATER-600", 1),
                line("BEV-COLA-1500", 1)));

        assertThat(normalized.lines()).extracting(CartLine::sku)
                .containsExactly("BEV-COLA-1500", "BEV-WATER-600", "SNK-CHIPS-45");
    }

    @Test
    void normalizingANormalizedCartChangesNothing() {
        Cart normalized = CartNormalizer.normalize(cart(
                line("SNK-CHIPS-45", 2),
                line("BEV-COLA-1500", 3),
                line("SNK-CHIPS-45", 1)));

        assertThat(CartNormalizer.normalize(normalized)).isEqualTo(normalized);
    }

    @Test
    void theBreakdownDoesNotDependOnTheOrderOfTheClientLines() {
        List<CartLine> lines = List.of(
                line("SNK-CHIPS-45", 2),
                line("BEV-WATER-600", 4),
                line("BEV-COLA-1500", 3),
                line("SNK-CHIPS-45", 1));
        Breakdown reference = engine.price(new Cart(lines), CO_ALPHA);

        for (List<CartLine> permutation : permutations(lines)) {
            assertThat(engine.price(new Cart(permutation), CO_ALPHA))
                    .as("breakdown of %s", permutation)
                    .isEqualTo(reference);
        }
        assertThat(reference.lines()).extracting(LineBreakdown::sku, LineBreakdown::qty)
                .containsExactly(
                        tuple("BEV-COLA-1500", 3L),
                        tuple("BEV-WATER-600", 4L),
                        tuple("SNK-CHIPS-45", 3L));
    }

    @Test
    void aRepeatedSkuPricesExactlyLikeOneMergedLine() {
        Breakdown split = engine.price(cart(line("SNK-CHIPS-45", 2), line("SNK-CHIPS-45", 1)), CO_ALPHA);
        Breakdown merged = engine.price(cart(line("SNK-CHIPS-45", 3)), CO_ALPHA);

        assertThat(split).isEqualTo(merged);
    }

    @Test
    void rejectsAnEmptyCart() {
        assertRejected(() -> CartNormalizer.normalize(cart()), Code.CART_EMPTY);
        assertRejected(() -> engine.price(cart(), CO_ALPHA), Code.CART_EMPTY);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsAQuantityBelowOne(long qty) {
        assertRejected(() -> CartNormalizer.normalize(cart(line("SNK-CHIPS-45", qty))), Code.INVALID_QUANTITY)
                .withMessageContaining("SNK-CHIPS-45");
        assertRejected(() -> engine.price(cart(line("SNK-CHIPS-45", qty)), CO_ALPHA), Code.INVALID_QUANTITY);
    }

    @Test
    void acceptsAQuantityOfOne() {
        assertThat(CartNormalizer.normalize(cart(line("SNK-CHIPS-45", 1))).lines())
                .containsExactly(line("SNK-CHIPS-45", 1));
    }

    @Test
    void checksEachLineBeforeMergingTheLinesOfARepeatedSku() {
        assertRejected(() -> CartNormalizer.normalize(cart(line("SNK-CHIPS-45", -5), line("SNK-CHIPS-45", 10))),
                Code.INVALID_QUANTITY);
        assertRejected(() -> CartNormalizer.normalize(cart(line("SNK-CHIPS-45", 10), line("SNK-CHIPS-45", -5))),
                Code.INVALID_QUANTITY);
    }

    @Test
    void rejectsAMergedQuantityThatDoesNotFitALong() {
        assertRejected(() -> CartNormalizer.normalize(
                        cart(line("SNK-CHIPS-45", Long.MAX_VALUE), line("SNK-CHIPS-45", 1))),
                Code.INVALID_QUANTITY)
                .withMessageContaining("SNK-CHIPS-45");
    }

    @Test
    void rejectsASkuTheTenantDoesNotSell() {
        assertRejected(() -> engine.price(cart(line("BEV-TEA-500", 1)), CO_ALPHA), Code.UNKNOWN_SKU)
                .withMessageContaining("BEV-TEA-500");
    }

    @Test
    void rejectsASkuThatOnlyAnotherTenantSells() {
        // SNK-PLANTAIN-45 is in the ec-alpha catalog only; the co-alpha snapshot does not contain it.
        assertRejected(() -> engine.price(cart(line("BEV-COLA-1500", 1), line("SNK-PLANTAIN-45", 1)), CO_ALPHA),
                Code.UNKNOWN_SKU)
                .withMessageContaining("SNK-PLANTAIN-45");
    }

    @Test
    void rejectsAnInvalidQuantityBeforeAnUnknownSku() {
        assertRejected(() -> engine.price(cart(line("AAA-UNKNOWN", 1), line("SNK-CHIPS-45", 0)), CO_ALPHA),
                Code.INVALID_QUANTITY);
        assertRejected(() -> engine.price(cart(line("SNK-CHIPS-45", 0), line("AAA-UNKNOWN", 1)), CO_ALPHA),
                Code.INVALID_QUANTITY);
    }

    @Test
    void namesTheFirstRejectedSkuInSkuOrderWhateverTheOrderOfTheClientLines() {
        assertRejected(() -> CartNormalizer.normalize(cart(line("SNK-CHIPS-45", 0), line("BEV-WATER-600", 0))),
                Code.INVALID_QUANTITY)
                .withMessageContaining("BEV-WATER-600");
        assertRejected(() -> CartNormalizer.normalize(cart(line("BEV-WATER-600", 0), line("SNK-CHIPS-45", 0))),
                Code.INVALID_QUANTITY)
                .withMessageContaining("BEV-WATER-600");
        assertRejected(() -> engine.price(cart(line("ZZZ-UNKNOWN", 1), line("AAA-UNKNOWN", 1)), CO_ALPHA),
                Code.UNKNOWN_SKU)
                .withMessageContaining("AAA-UNKNOWN");
    }

    private static ThrowableAssertAlternative<CartRejectedException> assertRejected(
            ThrowingCallable call, Code code) {
        return assertThatExceptionOfType(CartRejectedException.class)
                .isThrownBy(call)
                .satisfies(rejection -> assertThat(rejection.code()).isEqualTo(code));
    }

    private static List<List<CartLine>> permutations(List<CartLine> lines) {
        if (lines.isEmpty()) {
            return List.of(List.of());
        }
        List<List<CartLine>> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<CartLine> rest = new ArrayList<>(lines);
            CartLine first = rest.remove(i);
            for (List<CartLine> tail : permutations(rest)) {
                List<CartLine> permutation = new ArrayList<>();
                permutation.add(first);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
