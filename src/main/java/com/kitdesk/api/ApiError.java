package com.kitdesk.api;

import com.kitdesk.config.CorrelationIdFilter;

/**
 * The single error shape every failed request returns (the contract's {@code Error} schema):
 * a machine-readable code, a human-readable message, and the request's correlation ID.
 * Never a stack trace.
 */
public record ApiError(String code, String message, String correlationId) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, CorrelationIdFilter.current());
    }
}
