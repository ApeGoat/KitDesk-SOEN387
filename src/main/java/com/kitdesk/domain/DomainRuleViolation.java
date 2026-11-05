package com.kitdesk.domain;

/**
 * Thrown when a request would break a rule. {@code code} is the contract's error code (e.g.
 * UNIT_UNAVAILABLE); the interface layer maps it to an HTTP status (see the service layer's
 * {@code ErrorCatalog}). Entities throw it for their own rules; the service layer throws it for
 * the rules that look across many records (category limit, subscription, overdue gear) and for
 * access checks.
 */
public class DomainRuleViolation extends RuntimeException {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String UNIT_UNAVAILABLE = "UNIT_UNAVAILABLE";
    public static final String INVALID_RESERVATION_STATE = "INVALID_RESERVATION_STATE";

    private final String code;

    public DomainRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
