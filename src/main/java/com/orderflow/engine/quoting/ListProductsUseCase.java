package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import com.orderflow.engine.quoting.QuoteRejectedException.Code;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists what a tenant sells (api-contract E-07), so a client can build a cart. It lives in quoting because it lists
 * exactly what a quote can price: the tenant's products, with the list price and tax rate a new quote starts from.
 * Levers, families, product ids and the customer's credit are not part of it, so a list price is informative and only
 * a quote prices a cart (E-01). It only reads, since catalog administration stays out of scope (SPEC §1). The caller
 * is checked as a quote checks it: an unknown tenant is rejected, and a customer the tenant does not have is not
 * found.
 */
public class ListProductsUseCase {

    private final SnapshotProvider snapshots;
    private final ProductCatalog catalog;

    public ListProductsUseCase(SnapshotProvider snapshots, ProductCatalog catalog) {
        this.snapshots = snapshots;
        this.catalog = catalog;
    }

    /**
     * The tenant's products in SKU order, in the tenant's currency.
     *
     * @throws QuoteRejectedException when the tenant does not exist, or the customer does not belong to it
     */
    @Transactional(readOnly = true)
    public ProductView list(String tenantId, UUID customerId) {
        TenantPolicy policy = snapshots.tenantPolicy(tenantId).orElseThrow(() -> new QuoteRejectedException(
                Code.TENANT_NOT_FOUND, "Tenant '" + tenantId + "' does not exist"));
        if (!snapshots.customerExists(tenantId, customerId)) {
            throw new QuoteRejectedException(Code.CUSTOMER_NOT_FOUND,
                    "Customer " + customerId + " does not belong to tenant '" + tenantId + "'");
        }
        CurrencyPolicy currency = policy.currency();
        return new ProductView(currency.currencyCode(), catalog.products(tenantId, currency));
    }
}
