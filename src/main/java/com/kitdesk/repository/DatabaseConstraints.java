package com.kitdesk.repository;

import org.hibernate.exception.ConstraintViolationException;

/** Recognises which database rule refused a write, so the service can give the right error. */
public final class DatabaseConstraints {

    private DatabaseConstraints() {
    }

    /**
     * True if {@code e} (or one of its causes) is the exclusion constraint refusing an overlapping
     * active reservation for the same unit: the backstop behind the row locks.
     */
    public static boolean isReservationOverlap(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && ReservationRepository.OVERLAP_CONSTRAINT.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(ReservationRepository.OVERLAP_CONSTRAINT)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
