package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.quoting.CreateQuoteUseCase;
import com.orderflow.engine.quoting.GetQuoteUseCase;
import com.orderflow.engine.quoting.QuoteView;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The quote endpoints (api-contract E-01 and E-02). Tenant and customer come from headers (A-06). A missing header,
 * an unreadable body or a customer id that is not a UUID is a malformed request, VALIDATION_FAILED.
 */
@RestController
@RequestMapping(QuoteController.PATH)
public class QuoteController {

    static final String PATH = "/api/v1/quotes";
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String CUSTOMER_HEADER = "X-Customer-Id";

    private final CreateQuoteUseCase createQuote;
    private final GetQuoteUseCase getQuote;

    public QuoteController(CreateQuoteUseCase createQuote, GetQuoteUseCase getQuote) {
        this.createQuote = createQuote;
        this.getQuote = getQuote;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<QuoteResponse> create(@RequestHeader(TENANT_HEADER) String tenantId,
            @RequestHeader(CUSTOMER_HEADER) UUID customerId, @RequestBody CreateQuoteRequest request) {
        if (!request.isComplete()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        QuoteView quote = createQuote.create(tenantId, customerId, request.paymentMethod(), request.toCart());
        return ResponseEntity.created(URI.create(PATH + "/" + quote.quoteId())).body(QuoteResponse.from(quote));
    }

    @GetMapping("/{quoteId}")
    QuoteResponse get(@RequestHeader(TENANT_HEADER) String tenantId, @RequestHeader(CUSTOMER_HEADER) UUID customerId,
            @PathVariable UUID quoteId) {
        return QuoteResponse.from(getQuote.get(tenantId, customerId, quoteId));
    }
}
