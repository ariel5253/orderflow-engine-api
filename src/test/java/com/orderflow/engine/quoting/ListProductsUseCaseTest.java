package com.orderflow.engine.quoting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot;
import com.orderflow.engine.pricing.domain.lever.GiftTaxPolicy;
import com.orderflow.engine.quoting.ProductView.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-53: the product list checks its caller as a quote does, before it reads any product, and lists the tenant's
 * products in SKU order, in the currency of the tenant's country.
 */
class ListProductsUseCaseTest {

    private static final String TENANT = "ec-alpha";
    private static final UUID CUSTOMER = UUID.fromString("01a13cdb-cc00-7002-8001-000000000002");
    private static final CurrencyPolicy USD = new CurrencyPolicy("USD", 2, RoundingMode.HALF_UP);
    private static final TenantPolicy POLICY = new TenantPolicy(USD, GiftTaxPolicy.EXEMPT, Duration.ofMinutes(15));

    private final Snapshots snapshots = new Snapshots();
    private final Catalog catalog = new Catalog();
    private final ListProductsUseCase useCase = new ListProductsUseCase(snapshots, catalog);

    @Test
    void listsTheTenantsProductsInSkuOrderInTheTenantsCurrency() {
        catalog.products = List.of(item("SNK-PLANTAIN-45", "0.55"), item("BEV-COLA-350", "0.60"),
                item("BEV-COLA-1500", "1.45"));

        ProductView products = useCase.list(TENANT, CUSTOMER);

        assertThat(products.currency()).isEqualTo("USD");
        assertThat(products.items()).extracting(Item::sku)
                .containsExactly("BEV-COLA-1500", "BEV-COLA-350", "SNK-PLANTAIN-45");
        assertThat(catalog.reads).containsExactly(TENANT + " in USD");
    }

    @Test
    void aTenantWithNoProductStillGivesItsCurrency() {
        ProductView products = useCase.list(TENANT, CUSTOMER);

        assertThat(products).isEqualTo(new ProductView("USD", List.of()));
    }

    @Test
    void anUnknownTenantIsRejectedBeforeAnyProductIsRead() {
        snapshots.policy = Optional.empty();

        assertThatThrownBy(() -> useCase.list(TENANT, CUSTOMER))
                .isInstanceOfSatisfying(QuoteRejectedException.class, rejected -> assertThat(rejected.code())
                        .isEqualTo(QuoteRejectedException.Code.TENANT_NOT_FOUND));
        assertThat(catalog.reads).isEmpty();
    }

    @Test
    void aCustomerTheTenantDoesNotHaveIsNotFoundBeforeAnyProductIsRead() {
        snapshots.customerExists = false;

        assertThatThrownBy(() -> useCase.list(TENANT, CUSTOMER))
                .isInstanceOfSatisfying(QuoteRejectedException.class, rejected -> assertThat(rejected.code())
                        .isEqualTo(QuoteRejectedException.Code.CUSTOMER_NOT_FOUND));
        assertThat(catalog.reads).isEmpty();
    }

    private static Item item(String sku, String listPrice) {
        return new Item(sku, sku, new Money(new BigDecimal(listPrice), USD), new BigDecimal("0.150000"));
    }

    /** One tenant with one customer, unless a test says otherwise. Only the product list's two reads are answered. */
    private static final class Snapshots implements SnapshotProvider {

        private Optional<TenantPolicy> policy = Optional.of(POLICY);
        private boolean customerExists = true;

        @Override
        public Optional<TenantPolicy> tenantPolicy(String tenantId) {
            return TENANT.equals(tenantId) ? policy : Optional.empty();
        }

        @Override
        public boolean customerExists(String tenantId, UUID customerId) {
            return customerExists && TENANT.equals(tenantId) && CUSTOMER.equals(customerId);
        }

        @Override
        public PricingSnapshot pricingSnapshot(String tenantId, TenantPolicy tenantPolicy, Set<String> skus) {
            throw new AssertionError("the product list prices nothing");
        }
    }

    /** Returns its products in the order it was given them, and records each read. */
    private static final class Catalog implements ProductCatalog {

        private List<Item> products = List.of();
        private final List<String> reads = new ArrayList<>();

        @Override
        public List<Item> products(String tenantId, CurrencyPolicy currency) {
            reads.add(tenantId + " in " + currency.currencyCode());
            return products;
        }
    }
}
