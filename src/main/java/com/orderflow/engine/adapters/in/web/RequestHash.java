package com.orderflow.engine.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells a retry of a confirmation from another request sent with the same idempotency key (SPEC section 7.1):
 * {@code request_hash = SHA-256(method + path + customerId + canonical JSON body)}. The body is canonical so that a
 * retry with its properties in another order or other whitespace is still the same request.
 */
final class RequestHash {

    /** Writes objects with their properties sorted by name and no whitespace between tokens. */
    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .build();

    private RequestHash() {
    }

    static byte[] of(String method, String path, UUID customerId, JsonNode body) {
        MessageDigest sha256 = sha256();
        sha256.update((method + path + customerId).getBytes(StandardCharsets.UTF_8));
        sha256.update(CANONICAL_JSON.writeValueAsBytes(body));
        return sha256.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException everyJavaPlatformHasIt) {
            throw new IllegalStateException("SHA-256 is not available", everyJavaPlatformHasIt);
        }
    }
}
