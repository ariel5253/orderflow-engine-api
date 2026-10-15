package com.orderflow.engine.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC BR-01 and AC-03: the engine runs its steps in the order of BR-01, on the cart normalized by
 * BR-00. Each lever task adds the cases that pin where its step runs.
 */
class PricingPipelineOrderTest {

    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);

    private static final PricingSnapshot EC_ALPHA = new PricingSnapshot(USD, List.of(
            product("BEV-COLA-1500", "1.45", "0.150000"),
            product("SNK-PLANTAIN-45", "0.55", "0.150000")));

    private final PricingEngine engine = new PricingEngine();

    private static Product product(String sku, String listPrice, String taxRate) {
        return new Product(sku, usd(listPrice), new BigDecimal(taxRate));
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    @Test
    void normalizesTheCartBeforeTheFirstStep() {
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("SNK-PLANTAIN-45", 1),
                new CartLine("BEV-COLA-1500", 2),
                new CartLine("BEV-COLA-1500", 1))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::qty, LineBreakdown::gross)
                .containsExactly(
                        tuple("BEV-COLA-1500", 3L, usd("4.35")),
                        tuple("SNK-PLANTAIN-45", 1L, usd("0.55")));
    }

    @Test
    void taxesEachLineOnItsTaxableBaseBeforeTheTotalIsSummed() {
        // 4.35 x 0.15 = 0.6525 and 0.55 x 0.15 = 0.0825. Rounded per line (step 6, RP-4) they give
        // 0.65 + 0.08 = 0.73. Taxing the order instead would round 4.90 x 0.15 = 0.735 to 0.74.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("BEV-COLA-1500", 3),
                new CartLine("SNK-PLANTAIN-45", 1))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(
                        tuple("BEV-COLA-1500", usd("4.35"), usd("0.65")),
                        tuple("SNK-PLANTAIN-45", usd("0.55"), usd("0.08")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(usd("4.90"), usd("4.90"), usd("0.73"), usd("5.63")));
        assertThat(breakdown.currency()).isEqualTo("USD");
    }

    @Test
    void roundsAHalfCentLineTaxWithTheRoundingModeOfTheSnapshotPolicy() {
        // 2 x 0.55 = 1.10 and 1.10 x 0.15 = 0.165, a tie at the USD scale. Step 6 rounds it once
        // with the policy's HALF_UP to 0.17 (RP-4); DOWN, FLOOR, HALF_DOWN and HALF_EVEN give 0.16.
        Breakdown breakdown = engine.price(new Cart(List.of(
                new CartLine("SNK-PLANTAIN-45", 2))), EC_ALPHA);

        assertThat(breakdown.lines())
                .extracting(LineBreakdown::sku, LineBreakdown::taxableBase, LineBreakdown::tax)
                .containsExactly(tuple("SNK-PLANTAIN-45", usd("1.10"), usd("0.17")));
        assertThat(breakdown.totals()).isEqualTo(new Totals(usd("1.10"), usd("1.10"), usd("0.17"), usd("1.27")));
    }
}
