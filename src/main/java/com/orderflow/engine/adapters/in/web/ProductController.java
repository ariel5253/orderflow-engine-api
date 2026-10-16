package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.quoting.ListProductsUseCase;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product list (api-contract E-07): what the tenant sells, so a client can build a cart. Tenant and customer come
 * from headers (A-06), checked as the quote endpoints check them: a missing header or a customer id that is not a UUID
 * is a malformed request, VALIDATION_FAILED. It has no CORS configuration, as no endpoint has: the web client reaches
 * the API through its own origin (architecture section 1.1).
 */
@RestController
@RequestMapping(ProductController.PATH)
public class ProductController {

    static final String PATH = "/api/v1/products";

    private final ListProductsUseCase listProducts;

    public ProductController(ListProductsUseCase listProducts) {
        this.listProducts = listProducts;
    }

    @GetMapping
    ProductListResponse list(@RequestHeader(QuoteController.TENANT_HEADER) String tenantId,
            @RequestHeader(QuoteController.CUSTOMER_HEADER) UUID customerId) {
        return ProductListResponse.from(listProducts.list(tenantId, customerId));
    }
}
