package com.kitdesk.domain;

/**
 * RESERVED and PICKED_UP are "active": they occupy the unit and count toward the category limit.
 * Overdue is not a status; see {@link Reservation#isOverdue}.
 */
public enum ReservationStatus {
    RESERVED,
    PICKED_UP,
    RETURNED,
    CANCELLED,
    EXPIRED;

    public boolean isActive() {
        return this == RESERVED || this == PICKED_UP;
    }
}
