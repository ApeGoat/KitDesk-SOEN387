package com.kitdesk.domain;

/**
 * Thrown by an entity when a request would break one of its own rules. {@code code} is the
 * contract's error code (e.g. UNIT_UNAVAILABLE); the interface layer maps it to an HTTP status.
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
