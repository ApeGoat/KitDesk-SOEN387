package com.kitdesk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Plain unit tests for the reservation's own rules: no Spring, no database. */
class ReservationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    final User student = new User("alice@kitdesk.test", "hash", "Alice", Role.STUDENT, true);
    final Category cameras = new Category(CategoryCode.CAMERA, "Cameras", 1);
    final EquipmentModel r5 = new EquipmentModel(cameras, "Canon", "EOS R5", null);
    final Unit unit = new Unit("CAM-R5-03", r5);

    Reservation book(LocalDate start, LocalDate end) {
        return Reservation.create(student, unit, start, end, TODAY, NOW);
    }

    static void assertRule(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainRuleViolation.class)
                .extracting(e -> ((DomainRuleViolation) e).getCode())
                .isEqualTo(code);
    }

    @Nested
    class DateWindow {

        @Test
        void aOneDayBookingIsAllowed() {
            Reservation r = book(TODAY, TODAY);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(r.getPublicId()).matches("res_[0-9a-f]{16}");
            assertThat(r.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void tenDaysIsTheMaximum() {
            // Contract example: the longest reservation starting 2026-11-02 ends 2026-11-11.
            assertThat(book(TODAY, LocalDate.of(2026, 11, 11)).getEndDate()).isEqualTo("2026-11-11");
        }

        @Test
        void elevenDaysIsRefused() {
            assertRule(() -> book(TODAY, LocalDate.of(2026, 11, 12)), DomainRuleViolation.INVALID_REQUEST);
        }

        @Test
        void endBeforeStartIsRefused() {
            assertRule(() -> book(TODAY.plusDays(3), TODAY.plusDays(2)), DomainRuleViolation.INVALID_REQUEST);
        }

        @Test
        void startInThePastIsRefused() {
            assertRule(() -> book(TODAY.minusDays(1), TODAY.plusDays(1)), DomainRuleViolation.INVALID_REQUEST);
        }

        @Test
        void startInTheFutureIsAllowed() {
            assertThat(book(TODAY.plusDays(30), TODAY.plusDays(39)).getStartDate()).isEqualTo(TODAY.plusDays(30));
        }

        @Test
        void missingDatesAreRefused() {
            assertRule(() -> book(null, TODAY), DomainRuleViolation.INVALID_REQUEST);
            assertRule(() -> book(TODAY, null), DomainRuleViolation.INVALID_REQUEST);
        }

        @Test
        void tenDayLimitHoldsAcrossMonthAndYearEnds() {
            LocalDate dec27 = LocalDate.of(2026, 12, 27);
            Reservation r = Reservation.create(student, unit, dec27, LocalDate.of(2027, 1, 5), dec27, NOW);
            assertThat(r.getEndDate()).isEqualTo("2027-01-05");
            assertRule(() -> Reservation.create(student, unit, dec27, LocalDate.of(2027, 1, 6), dec27, NOW),
                    DomainRuleViolation.INVALID_REQUEST);
        }
    }

    @Nested
    class Transitions {

        @Test
        void cancelBeforePickup() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.cancel(NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(r.getCancelledAt()).isEqualTo(NOW);
            assertThat(r.isActive()).isFalse();
        }

        @Test
        void cannotCancelTwice() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.cancel(NOW);
            assertRule(() -> r.cancel(NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void cannotCancelAfterPickup() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW);
            assertRule(() -> r.cancel(NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void pickupOnAnyDayInsideTheRange() {
            Reservation first = book(TODAY, TODAY.plusDays(4));
            first.pickUp(TODAY, NOW);
            assertThat(first.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);

            Reservation late = book(TODAY, TODAY.plusDays(4));
            late.pickUp(TODAY.plusDays(4), NOW); // last day still counts
            assertThat(late.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
            assertThat(late.getEndDate()).isEqualTo(TODAY.plusDays(4)); // late pickup never extends the end date
        }

        @Test
        void noPickupBeforeTheStartDate() {
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            assertRule(() -> r.pickUp(TODAY, NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void noPickupAfterTheEndDate() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertRule(() -> r.pickUp(TODAY.plusDays(3), NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void cannotPickUpTwice() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW);
            assertRule(() -> r.pickUp(TODAY, NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void returnOnlyAfterPickup() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertRule(() -> r.markReturned(NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
            r.pickUp(TODAY, NOW);
            r.markReturned(NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RETURNED);
            assertRule(() -> r.markReturned(NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void overdueGearCanStillBeReturned() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW);
            assertThat(r.isOverdue(TODAY.plusDays(10))).isTrue();
            r.markReturned(NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RETURNED);
        }
    }

    @Nested
    class ExpiryAndOverdue {

        @Test
        void unclaimedBookingStaysValidThroughItsEndDate() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertThat(r.expireIfUnclaimed(TODAY.plusDays(2))).isFalse();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void unclaimedBookingExpiresTheDayAfterItsEndDate() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertThat(r.expireIfUnclaimed(TODAY.plusDays(3))).isTrue();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(r.isActive()).isFalse();
        }

        @Test
        void pickedUpBookingNeverExpires() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW);
            assertThat(r.expireIfUnclaimed(TODAY.plusDays(30))).isFalse();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
        }

        @Test
        void overdueOnlyAfterTheEndDateAndOnlyWhenPickedUp() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertThat(r.isOverdue(TODAY.plusDays(5))).isFalse(); // never picked up: not overdue
            r.pickUp(TODAY, NOW);
            assertThat(r.isOverdue(TODAY.plusDays(2))).isFalse(); // last day: still on time
            assertThat(r.isOverdue(TODAY.plusDays(3))).isTrue();
        }
    }

    @Nested
    class Overlap {

        final Reservation r = book(TODAY.plusDays(5), TODAY.plusDays(8)); // days 5..8

        @Test
        void sharingOnlyTheLastDayOverlaps() {
            assertThat(r.overlaps(TODAY.plusDays(8), TODAY.plusDays(9))).isTrue();
        }

        @Test
        void sharingOnlyTheFirstDayOverlaps() {
            assertThat(r.overlaps(TODAY.plusDays(2), TODAY.plusDays(5))).isTrue();
        }

        @Test
        void adjacentRangesDoNotOverlap() {
            assertThat(r.overlaps(TODAY.plusDays(9), TODAY.plusDays(10))).isFalse();
            assertThat(r.overlaps(TODAY.plusDays(1), TODAY.plusDays(4))).isFalse();
        }

        @Test
        void containedAndContainingRangesOverlap() {
            assertThat(r.overlaps(TODAY.plusDays(6), TODAY.plusDays(6))).isTrue();
            assertThat(r.overlaps(TODAY.plusDays(1), TODAY.plusDays(10))).isTrue();
        }
    }
}
