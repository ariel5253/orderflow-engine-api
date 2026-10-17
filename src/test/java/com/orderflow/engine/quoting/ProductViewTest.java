package com.orderflow.engine.quoting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.quoting.ProductView.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC-53 and SPEC BR-00: the product list gives its products in the SKU order of a breakdown, whatever order they come
 * in, and every list price is in the one currency the list gives.
 */
class ProductViewTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    @Test
    void productsFollowTheSkuOrderOfABreakdown() {
        ProductView products = new ProductView("COP", List.of(cop("SNK-CHIPS-45"), cop("BEV-COLA-350"),
                cop("BEV-WATER-600"), cop("BEV-COLA-1500")));

        assertThat(products.items()).extracting(Item::sku)
                .containsExactly("BEV-COLA-1500", "BEV-COLA-350", "BEV-WATER-600", "SNK-CHIPS-45");
    }

    @Test
    void theListKeepsItsOwnCopyOfTheProducts() {
        List<Item> given = new ArrayList<>(List.of(cop("BEV-COLA-350")));
        ProductView products = new ProductView("COP", given);

        given.add(cop("BEV-COLA-1500"));

        assertThat(products.items()).extracting(Item::sku).containsExactly("BEV-COLA-350");
        assertThatThrownBy(() -> products.items().add(cop("SNK-CHIPS-45")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aListPriceInAnotherCurrencyIsRejected() {
        Item dollars = new Item("BEV-COLA-350", "Cola 350 ml", new Money(new BigDecimal("0.60"), USD),
                new BigDecimal("0.150000"));

        assertThatThrownBy(() -> new ProductView("COP", List.of(cop("BEV-COLA-1500"), dollars)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("List price of SKU 'BEV-COLA-350' is in USD, not in the list currency COP");
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException().isThrownBy(() -> new ProductView(null, List.of()))
                .withMessage("currency");
        assertThatNullPointerException().isThrownBy(() -> new ProductView("COP", null))
                .withMessage("items");
    }

    private static Item cop(String sku) {
        return new Item(sku, sku, new Money(new BigDecimal("2500"), COP), new BigDecimal("0.190000"));
    }
}
