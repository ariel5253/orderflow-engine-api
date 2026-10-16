package com.orderflow.engine.quoting;

import com.orderflow.engine.pricing.domain.CurrencyPolicy;
import java.util.List;

/**
 * Reads what a tenant sells, for the product list (api-contract E-07). The port only reads: products enter through the
 * seed migrations, and catalog administration stays out of scope (SPEC §1).
 */
public interface ProductCatalog {

    /**
     * The products of the tenant, in no particular order, each list price at the scale of the tenant's currency. A
     * product of another tenant is never read. A stored price with more decimals than the currency scale is rejected
     * when it is read (data model DM-01).
     */
    List<ProductView.Item> products(String tenantId, CurrencyPolicy currency);
}
