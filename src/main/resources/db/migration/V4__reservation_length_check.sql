-- Database backstop for the 10-day rule (start and end both included, so end - start <= 9).
-- Reservation.MAX_DAYS in the domain stays the main rule; this only catches a bug that slips
-- past it, the same way the exclusion constraint backs up the availability check.
-- Early pickup trims the end date, so it never breaks this.
ALTER TABLE reservations
    ADD CONSTRAINT reservation_max_10_days CHECK (end_date - start_date <= 9);
