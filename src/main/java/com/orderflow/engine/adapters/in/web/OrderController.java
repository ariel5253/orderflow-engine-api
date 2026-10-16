package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.ordering.Confirmation;
import com.orderflow.engine.ordering.ConfirmOrderUseCase;
import com.orderflow.engine.ordering.GetOrderUseCase;
import com.orderflow.engine.ordering.ListOrdersUseCase;
import java.math.BigInteger;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order endpoints (api-contract E-03 to E-05). Tenant and customer come from headers (A-06), and a confirmation
 * also needs the Idempotency-Key header. A missing tenant or customer header, a customer, order or quote id that is
 * not a UUID, an unreadable body, or a history size that is not a positive integer is a malformed request,
 * VALIDATION_FAILED.
 *
 * <p>The answer to a confirmation is written only after the use case returns, which is after its transaction
 * committed: a 201 never names an order that does not exist (I-201). Its body is the exact bytes stored under the
 * key, so a replay returns the same bytes, marked with {@code Idempotent-Replayed: true}. An order read back (E-04)
 * has the body of its confirmation, and a history (E-05) lists the caller's own orders, newest first.
 */
@RestController
@RequestMapping(OrderController.PATH)
public class OrderController {

    static final String PATH = "/api/v1/orders";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** How many orders a history lists when the request gives no size (E-05). */
    static final int DEFAULT_HISTORY_SIZE = 20;

    /** The most orders one history lists. A larger size is read as this one (E-05). */
    static final int MAX_HISTORY_SIZE = 100;

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** A history size is written in decimal digits only: no sign, no fraction and no exponent. */
    private static final Pattern HISTORY_SIZE = Pattern.compile("[0-9]+");

    /** The canonical text of a UUID, the only form a quote id is written in. */
    private static final Pattern QUOTE_ID =
            Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    private final ConfirmOrderUseCase confirmOrder;
    private final GetOrderUseCase getOrder;
    private final ListOrdersUseCase listOrders;
    private final JsonMapper json;

    /** {@code json} is the mapper of Spring MVC, so a stored order body is written as every other body is. */
    public OrderController(ConfirmOrderUseCase confirmOrder, GetOrderUseCase getOrder, ListOrdersUseCase listOrders,
            JsonMapper json) {
        this.confirmOrder = confirmOrder;
        this.getOrder = getOrder;
        this.listOrders = listOrders;
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

    @GetMapping("/{orderId}")
    OrderResponse get(@RequestHeader(QuoteController.TENANT_HEADER) String tenantId,
            @RequestHeader(QuoteController.CUSTOMER_HEADER) UUID customerId, @PathVariable UUID orderId) {
        return OrderResponse.from(getOrder.get(tenantId, customerId, orderId));
    }

    /** {@code customerId} names whose history is asked for, and only the caller's own is ever answered (BD-01). */
    @GetMapping
    OrderHistoryResponse history(@RequestHeader(QuoteController.TENANT_HEADER) String tenantId,
            @RequestHeader(QuoteController.CUSTOMER_HEADER) UUID callerId, @RequestParam UUID customerId,
            @RequestParam(required = false) String size) {
        return OrderHistoryResponse.from(listOrders.list(tenantId, callerId, customerId, historySize(size)));
    }

    /**
     * How many orders a history request asks for: {@link #DEFAULT_HISTORY_SIZE} when it gives no size, otherwise its
     * size, a positive integer of any length, read as {@link #MAX_HISTORY_SIZE} when it is larger.
     */
    private static int historySize(String size) {
        if (size == null) {
            return DEFAULT_HISTORY_SIZE;
        }
        if (!HISTORY_SIZE.matcher(size).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        BigInteger requested = new BigInteger(size);
        if (requested.signum() == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        return requested.min(BigInteger.valueOf(MAX_HISTORY_SIZE)).intValueExact();
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
