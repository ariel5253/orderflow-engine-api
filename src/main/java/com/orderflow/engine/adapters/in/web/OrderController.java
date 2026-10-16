package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.ordering.Confirmation;
import com.orderflow.engine.ordering.ConfirmOrderUseCase;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order confirmation endpoint (api-contract E-03). Tenant and customer come from headers (A-06), and the
 * Idempotency-Key header is required. A missing tenant or customer header, an unreadable body, or a body without a
 * quote id is a malformed request, VALIDATION_FAILED.
 *
 * <p>The answer is written only after the use case returns, which is after its transaction committed: a 201 never
 * names an order that does not exist (I-201). Its body is the exact bytes stored under the key, so a replay returns
 * the same bytes, marked with {@code Idempotent-Replayed: true}.
 */
@RestController
@RequestMapping(OrderController.PATH)
public class OrderController {

    static final String PATH = "/api/v1/orders";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** The canonical text of a UUID, the only form a quote id is written in. */
    private static final Pattern QUOTE_ID =
            Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    private final ConfirmOrderUseCase confirmOrder;
    private final JsonMapper json;

    /** {@code json} is the mapper of Spring MVC, so a stored order body is written as every other body is. */
    public OrderController(ConfirmOrderUseCase confirmOrder, JsonMapper json) {
        this.confirmOrder = confirmOrder;
        this.json = json;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> confirm(@RequestHeader(QuoteController.TENANT_HEADER) String tenantId,
            @RequestHeader(QuoteController.CUSTOMER_HEADER) UUID customerId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        if (idempotencyKey == null) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISSING);
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        UUID quoteId = quoteIdOf(body);
        byte[] requestHash = RequestHash.of(HttpMethod.POST.name(), PATH, customerId, body);

        Confirmation confirmation = confirmOrder.confirm(tenantId, customerId, idempotencyKey, requestHash, quoteId,
                order -> json.writeValueAsBytes(OrderResponse.from(order)));

        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(PATH + "/" + confirmation.orderId()))
                .contentType(MediaType.APPLICATION_JSON);
        if (confirmation.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(confirmation.responseBody());
    }

    /** The body is {@code {"quoteId": "<uuid>"}}. */
    private static UUID quoteIdOf(JsonNode body) {
        JsonNode quoteId = body.isObject() ? body.get("quoteId") : null;
        if (quoteId == null || !quoteId.isString() || !QUOTE_ID.matcher(quoteId.stringValue()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        return UUID.fromString(quoteId.stringValue());
    }
}
