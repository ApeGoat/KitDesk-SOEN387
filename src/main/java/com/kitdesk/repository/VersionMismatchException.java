package com.kitdesk.repository;

/**
 * The client's If-Match-Version no longer matches the stored reservation: someone changed it
 * since the client last read it. Maps to 409 RESERVATION_VERSION_MISMATCH.
 */
public class VersionMismatchException extends RuntimeException {

    public VersionMismatchException(String reservationId, int expected, int actual) {
        super("Reservation " + reservationId + " is at version " + actual + ", not " + expected
                + ". Re-read it and try again.");
    }
}
