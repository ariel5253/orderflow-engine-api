package com.orderflow.engine.adapters.in.web;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that raise every kind of error the advice renders, because no production
 * endpoint exists before the quote and order controllers. {@link TestComponent} keeps it out of
 * component scanning: only the tests that import it see it.
 */
@TestComponent
@RestController
@RequestMapping(ProblemProbeController.BASE_PATH)
class ProblemProbeController {

    static final String BASE_PATH = "/test/problems";
    static final String UNEXPECTED_PATH = BASE_PATH + "/unexpected";
    static final String BODY_PATH = BASE_PATH + "/body";
    static final String INTERNAL_DETAIL = "SELECT secret_column FROM credit_account";

    @GetMapping("/{code}")
    void reject(@PathVariable ErrorCode code) {
        throw new ApiException(code);
    }

    @GetMapping("/unexpected")
    void fail() {
        throw new IllegalStateException(INTERNAL_DETAIL);
    }

    @PostMapping(path = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    Payload echo(@RequestBody Payload payload) {
        return payload;
    }

    record Payload(String value) {
    }
}
