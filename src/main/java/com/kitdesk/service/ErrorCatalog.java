package com.kitdesk.service;

import java.util.Map;

/**
 * The contract's error catalogue ({@code components.x-error-catalog} in openapi.yaml): every
 * error code and the HTTP status it comes with. The interface layer uses it to turn a
 * {@link com.kitdesk.domain.DomainRuleViolation} into a response, and the idempotency service
 * uses it to save 4xx results.
 */
public final class ErrorCatalog {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String SUBSCRIPTION_INACTIVE = "SUBSCRIPTION_INACTIVE";
    public static final String OVERDUE_GEAR = "OVERDUE_GEAR";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNIT_UNAVAILABLE = "UNIT_UNAVAILABLE";
    public static final String CATEGORY_LIMIT_REACHED = "CATEGORY_LIMIT_REACHED";
    public static final String INVALID_RESERVATION_STATE = "INVALID_RESERVATION_STATE";
    public static final String RESERVATION_VERSION_MISMATCH = "RESERVATION_VERSION_MISMATCH";
    public static final String IDEMPOTENCY_KEY_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String VERSION_REQUIRED = "VERSION_REQUIRED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry(INVALID_REQUEST, 400),
            Map.entry(INVALID_CREDENTIALS, 401),
            Map.entry(UNAUTHORIZED, 401),
            Map.entry(FORBIDDEN, 403),
            Map.entry(SUBSCRIPTION_INACTIVE, 403),
            Map.entry(OVERDUE_GEAR, 403),
            Map.entry(NOT_FOUND, 404),
            Map.entry(UNIT_UNAVAILABLE, 409),
            Map.entry(CATEGORY_LIMIT_REACHED, 409),
            Map.entry(INVALID_RESERVATION_STATE, 409),
            Map.entry(RESERVATION_VERSION_MISMATCH, 409),
            Map.entry(IDEMPOTENCY_KEY_REQUIRED, 422),
            Map.entry(IDEMPOTENCY_KEY_REUSED, 422),
            Map.entry(VERSION_REQUIRED, 428),
            Map.entry(INTERNAL_ERROR, 500),
            Map.entry(SERVICE_UNAVAILABLE, 503));

    private ErrorCatalog() {
    }

    /** HTTP status for a code. An unknown code is a bug on our side, so it maps to 500. */
    public static int statusOf(String code) {
        return STATUS.getOrDefault(code, 500);
    }
}
