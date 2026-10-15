package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PricingSnapshotTest {

    private static final CurrencyPolicy COP = new CurrencyPolicy("COP", 0, RoundingMode.HALF_UP);
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static Product product(String sku, CurrencyPolicy policy, String listPrice, String taxRate) {
        return new Product(sku, new Money(new BigDecimal(listPrice), policy), new BigDecimal(taxRate));
    }

    @Test
    void findsAProductByItsSkuCode() {
        Product cola = product("BEV-COLA-1500", COP, "5200", "0.190000");
        PricingSnapshot snapshot = new PricingSnapshot(COP, List.of(cola));

        assertThat(snapshot.policy()).isEqualTo(COP);
        assertThat(snapshot.product("BEV-COLA-1500")).contains(cola);
        assertThat(snapshot.product("BEV-TEA-500")).isEmpty();
    }

    @Test
    void rejectsAProductPricedWithAnotherPolicy() {
        List<Product> products = List.of(product("BEV-COLA-1500", USD, "1.45", "0.150000"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PricingSnapshot(COP, products))
                .withMessageContaining("BEV-COLA-1500")
                .withMessageContaining("USD")
                .withMessageContaining("COP");
    }

    @Test
    void rejectsASkuListedTwice() {
        List<Product> products = List.of(
                product("BEV-COLA-1500", COP, "5200", "0.190000"),
                product("BEV-COLA-1500", COP, "5300", "0.190000"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PricingSnapshot(COP, products))
                .withMessageContaining("BEV-COLA-1500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000000", "0.190000", "1", "1.000000"})
    void acceptsATaxRateFromZeroToOne(String taxRate) {
        assertThat(product("BEV-COLA-1500", COP, "5200", taxRate).taxRate()).isEqualByComparingTo(taxRate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.000001", "1.000001", "19"})
    void rejectsATaxRateOutsideZeroToOne(String taxRate) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> product("BEV-COLA-1500", COP, "5200", taxRate))
                .withMessageContaining("BEV-COLA-1500")
                .withMessageContaining(taxRate);
    }

    @Test
    void rejectsMissingValues() {
        Money price = new Money(new BigDecimal("5200"), COP);
        BigDecimal rate = new BigDecimal("0.190000");

        assertThatNullPointerException().isThrownBy(() -> new Product(null, price, rate)).withMessage("sku");
        assertThatNullPointerException().isThrownBy(() -> new Product("BEV-COLA-1500", null, rate))
                .withMessage("listPrice");
        assertThatNullPointerException().isThrownBy(() -> new Product("BEV-COLA-1500", price, null))
                .withMessage("taxRate");
        assertThatNullPointerException().isThrownBy(() -> new PricingSnapshot(null, List.of()))
                .withMessage("policy");
    }
}
