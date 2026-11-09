package com.kitdesk.repository;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Recognises which database rule refused a write, so the service can give the right error. */
public final class DatabaseConstraints {

    /** PostgreSQL's SQL state for "exclusion constraint violated". */
    static final String EXCLUSION_VIOLATION = "23P01";

    private DatabaseConstraints() {
    }

    /**
     * True if {@code e} (or one of its causes) is the exclusion constraint refusing an overlapping
     * active reservation for the same unit: the backstop behind the row locks.
     *
     * <p>Reads the structured error PostgreSQL sends back (SQL state 23P01 and the constraint's
     * name), not the wording of the message, which could change between versions or languages.
     */
    public static boolean isReservationOverlap(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && ReservationRepository.OVERLAP_CONSTRAINT.equals(cve.getConstraintName())) {
                return true;
            }
            if (t instanceof PSQLException pg && EXCLUSION_VIOLATION.equals(pg.getSQLState())) {
                ServerErrorMessage details = pg.getServerErrorMessage();
                if (details != null && ReservationRepository.OVERLAP_CONSTRAINT.equals(details.getConstraint())) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
