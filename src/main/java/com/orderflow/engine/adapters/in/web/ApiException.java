package com.orderflow.engine.adapters.in.web;

/**
 * A request that the web adapter rejects with a catalog code. It carries no user text: the
 * {@link ProblemDetailsAdvice} resolves the title and detail in the caller's language.
 */
public final class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
