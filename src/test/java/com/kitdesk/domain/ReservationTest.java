package com.kitdesk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
            r.cancel(TODAY, NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(r.getCancelledAt()).isEqualTo(NOW);
            assertThat(r.isActive()).isFalse();
        }

        @Test
        void cancelOnTheEndDateItselfIsAllowed() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.cancel(TODAY.plusDays(2), NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }

        @Test
        void cancelAfterTheEndDateIsRefusedBecauseTheBookingHasLapsed() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertRule(() -> r.cancel(TODAY.plusDays(3), NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void cannotCancelTwice() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.cancel(TODAY, NOW);
            assertRule(() -> r.cancel(TODAY, NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void cannotCancelAfterPickup() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW, List.of(), List.of());
            assertRule(() -> r.cancel(TODAY, NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void pickupOnAnyDayInsideTheRange() {
            Reservation first = book(TODAY, TODAY.plusDays(4));
            first.pickUp(TODAY, NOW, List.of(), List.of());
            assertThat(first.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);

            Reservation late = book(TODAY, TODAY.plusDays(4));
            late.pickUp(TODAY.plusDays(4), NOW, List.of(), List.of()); // last day still counts
            assertThat(late.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
            assertThat(late.getEndDate()).isEqualTo(TODAY.plusDays(4)); // late pickup never extends the end date
        }


        @Test
        void noPickupAfterTheEndDate() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertRule(() -> r.pickUp(TODAY.plusDays(3), NOW, List.of(), List.of()), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void cannotPickUpTwice() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW, List.of(), List.of());
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(), List.of()), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void returnOnlyAfterPickup() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertRule(() -> r.markReturned(NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
            r.pickUp(TODAY, NOW, List.of(), List.of());
            r.markReturned(NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RETURNED);
            assertRule(() -> r.markReturned(NOW), DomainRuleViolation.INVALID_RESERVATION_STATE);
        }

        @Test
        void overdueGearCanStillBeReturned() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW, List.of(), List.of());
            assertThat(r.isOverdue(TODAY.plusDays(10))).isTrue();
            r.markReturned(NOW);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RETURNED);
        }
    }

    @Nested
    class EarlyPickup {

        // Bob's booking of the same unit, used to block or not block Alice's early pickup.
        final User bob = new User("bob@kitdesk.test", "hash", "Bob", Role.STUDENT, true);

        Reservation bobs(int fromDay, int toDay) {
            return Reservation.create(bob, unit, TODAY.plusDays(fromDay), TODAY.plusDays(toDay), TODAY, NOW);
        }

        @Test
        void earlyPickupOfAFreeUnitMovesTheStartToToday() {
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            r.pickUp(TODAY, NOW, List.of(r), List.of());
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
            assertThat(r.getStartDate()).isEqualTo(TODAY);
            assertThat(r.getEndDate()).isEqualTo(TODAY.plusDays(5)); // end never moves
        }

        @Test
        void refusedWhileTheUnitIsStillOutWithSomeoneElse() {
            Reservation bobHasIt = bobs(0, 1);
            bobHasIt.pickUp(TODAY, NOW, List.of(), List.of());
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(bobHasIt, r), List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
            assertThat(r.getStartDate()).isEqualTo(TODAY.plusDays(3)); // nothing changed
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void refusedWhileAnOverdueHolderStillHasTheUnit() {
            // Bob's booking ended in the past but he never brought it back: the unit isn't here.
            Reservation bobsOld = Reservation.create(bob, unit, TODAY.minusDays(5), TODAY.minusDays(3), TODAY.minusDays(5), NOW);
            bobsOld.pickUp(TODAY.minusDays(5), NOW, List.of(), List.of());
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(bobsOld, r), List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
        }

        @Test
        void refusedWhenAnotherBookingAwaitsPickupInTheGap() {
            Reservation bobLater = bobs(1, 2);  // between today and Alice's start
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(bobLater, r), List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
        }

        @Test
        void aBookingReturnedThisMorningDoesNotBlock() {
            Reservation bobsFinished = bobs(0, 0);
            bobsFinished.pickUp(TODAY, NOW, List.of(), List.of());
            bobsFinished.markReturned(NOW); // returned this morning
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            r.pickUp(TODAY, NOW, List.of(bobsFinished, r), List.of());
            assertThat(r.getStartDate()).isEqualTo(TODAY);
        }

        @Test
        void bookingsAfterTheOriginalStartDoNotBlock() {
            Reservation bobAfter = bobs(6, 8);
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            r.pickUp(TODAY, NOW, List.of(bobAfter, r), List.of());
            assertThat(r.getStartDate()).isEqualTo(TODAY);
        }

        @Test
        void cancelledBookingsInTheGapDoNotBlock() {
            Reservation bobCancelled = bobs(1, 2);
            bobCancelled.cancel(TODAY, NOW);
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            r.pickUp(TODAY, NOW, List.of(bobCancelled, r), List.of());
            assertThat(r.getStartDate()).isEqualTo(TODAY);
        }

        @Test
        void endDateMovesBackSoTheBookingStaysAtTenDays() {
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(12)); // 10 days already
            r.pickUp(TODAY, NOW, List.of(r), List.of());
            assertThat(r.getStartDate()).isEqualTo(TODAY);
            assertThat(r.getEndDate()).isEqualTo(TODAY.plusDays(9));
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
        }

        @Test
        void shortBookingKeepsItsEndDate() {
            Reservation r = book(TODAY.plusDays(2), TODAY.plusDays(4));
            r.pickUp(TODAY, NOW, List.of(r), List.of());
            assertThat(r.getEndDate()).isEqualTo(TODAY.plusDays(4));
        }

        @Test
        void refusedEarlyPickupChangesNeitherDate() {
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(12));
            Reservation gap = book(TODAY.plusDays(1), TODAY.plusDays(2));
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(r, gap), List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
            assertThat(r.getStartDate()).isEqualTo(TODAY.plusDays(3));
            assertThat(r.getEndDate()).isEqualTo(TODAY.plusDays(12));
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void earlyPickupUpToTheTenDayLimitIsFine() {
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(9)); // today..day 9 = 10 days
            r.pickUp(TODAY, NOW, List.of(r), List.of());
            assertThat(r.getStartDate()).isEqualTo(TODAY);
        }
    }

    @Nested
    class UnitMustBeBackForAnyPickup {

        final User bob = new User("bob@kitdesk.test", "hash", "Bob", Role.STUDENT, true);

        /** Bob picked the unit up 5 days ago for 3 days and still hasn't brought it back. */
        Reservation bobsOverdue() {
            Reservation old = Reservation.create(bob, unit, TODAY.minusDays(5), TODAY.minusDays(3), TODAY.minusDays(5), NOW);
            old.pickUp(TODAY.minusDays(5), NOW, List.of(), List.of());
            return old;
        }

        @Test
        void onTimePickupRefusedWhileTheUnitIsOverdueWithSomeoneElse() {
            Reservation bobs = bobsOverdue();
            Reservation r = book(TODAY, TODAY.plusDays(2));   // starts today
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(bobs, r), List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(r.getPickedUpAt()).isNull();
        }

        @Test
        void latePickupRefusedInTheSameSituation() {
            Reservation bobs = bobsOverdue();
            Reservation r = book(TODAY, TODAY.plusDays(4));
            LocalDate dayTwo = TODAY.plusDays(2);   // between start and end
            assertRule(() -> r.pickUp(dayTwo, NOW, List.of(bobs, r), List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void onceTheUnitIsReturnedThePickupGoesThrough() {
            Reservation bobs = bobsOverdue();
            bobs.markReturned(NOW);
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW, List.of(bobs, r), List.of());
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
        }
    }

    @Nested
    class OccupiedUntil {

        @Test
        void aReservedBookingOccupiesItsUnitThroughItsEndDate() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertThat(r.occupiedUntil(TODAY.plusDays(5))).isEqualTo(TODAY.plusDays(2));
        }

        @Test
        void aPickedUpUnitIsOccupiedUntilReturnedEvenPastItsEndDate() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            r.pickUp(TODAY, NOW, List.of(), List.of());
            assertThat(r.occupiedUntil(TODAY.plusDays(1))).isEqualTo(TODAY.plusDays(2));   // not late yet
            assertThat(r.occupiedUntil(TODAY.plusDays(5))).isEqualTo(TODAY.plusDays(5));   // overdue: busy today
            r.markReturned(NOW);
            assertThat(r.occupies(TODAY.plusDays(5), TODAY.plusDays(5), TODAY.plusDays(5))).isFalse();
        }
    }

    @Nested
    class CategoryLimitPerDay {

        final Category lenses = new Category(CategoryCode.LENS, "Lenses", 2);
        final EquipmentModel fifty = new EquipmentModel(lenses, "Canon", "RF 50mm f/1.8", null);

        Reservation camera(int fromDay, int toDay) {
            return Reservation.create(student, new Unit("CAM-" + fromDay + "-" + toDay, r5),
                    TODAY.plusDays(fromDay), TODAY.plusDays(toDay), TODAY, NOW);
        }

        Reservation lens(int fromDay, int toDay) {
            return Reservation.create(student, new Unit("LEN-" + fromDay + "-" + toDay, fifty),
                    TODAY.plusDays(fromDay), TODAY.plusDays(toDay), TODAY, NOW);
        }

        void check(Category c, int fromDay, int toDay, List<Reservation> held) {
            Reservation.checkCategoryLimit(c, TODAY.plusDays(fromDay), TODAY.plusDays(toDay), TODAY, held);
        }

        @Test
        void aSecondCameraForADifferentWeekIsFine() {
            List<Reservation> held = List.of(camera(1, 3));
            check(cameras, 5, 7, held);   // no shared day
            assertRule(() -> check(cameras, 3, 5, held), DomainRuleViolation.CATEGORY_LIMIT_REACHED);   // shares day 3
        }

        @Test
        void theLimitIsPerDayNotATotal() {
            // Limit 2. Days 1-2 and 5-6 are taken once each; a new booking for days 1-6 never
            // makes any single day hold more than 2, so it's allowed (a total count would say 2).
            check(lenses, 1, 6, List.of(lens(1, 2), lens(5, 6)));
            assertRule(() -> check(lenses, 1, 6, List.of(lens(1, 2), lens(2, 3))),
                    DomainRuleViolation.CATEGORY_LIMIT_REACHED);   // day 2 already has 2
        }

        @Test
        void finishedBookingsDontCount() {
            Reservation cancelled = camera(1, 3);
            cancelled.cancel(TODAY, NOW);
            check(cameras, 1, 3, List.of(cancelled));
        }

        @Test
        void anOverdueCameraBlocksANewCameraStartingToday() {
            Reservation old = Reservation.create(student, new Unit("CAM-OLD", r5),
                    TODAY.minusDays(5), TODAY.minusDays(3), TODAY.minusDays(5), NOW);
            old.pickUp(TODAY.minusDays(5), NOW, List.of(), List.of());   // never brought back
            assertRule(() -> check(cameras, 0, 2, List.of(old)), DomainRuleViolation.CATEGORY_LIMIT_REACHED);
        }

        @Test
        void earlyPickupThatWouldGoOverTheLimitOnAnAddedDayIsRefused() {
            Reservation other = camera(0, 1);           // Alice already has a camera today and tomorrow
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            // Picking r up today would add days 0-2, and days 0-1 already have a camera.
            assertRule(() -> r.pickUp(TODAY, NOW, List.of(r), List.of(other, r)),
                    DomainRuleViolation.CATEGORY_LIMIT_REACHED);
            assertThat(r.getStartDate()).isEqualTo(TODAY.plusDays(3));
            assertThat(r.getEndDate()).isEqualTo(TODAY.plusDays(5));
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void earlyPickupDoesNotCountTheBookingAgainstItself() {
            Reservation r = book(TODAY.plusDays(3), TODAY.plusDays(5));
            r.pickUp(TODAY, NOW, List.of(r), List.of(r));
            assertThat(r.getStartDate()).isEqualTo(TODAY);
        }
    }

    @Nested
    class ReassignToIdenticalUnit {

        final Unit sameModel = new Unit("CAM-R5-01", r5);
        final Unit otherModel = new Unit("CAM-A7-01", new EquipmentModel(cameras, "Sony", "A7 IV", null));
        final User bob = new User("bob@kitdesk.test", "hash", "Bob", Role.STUDENT, true);

        @Test
        void movesToAFreeUnitOfTheSameModelKeepingTheDates() {
            Reservation r = book(TODAY.plusDays(1), TODAY.plusDays(3));
            r.reassignTo(sameModel, TODAY, List.of());
            assertThat(r.getUnit()).isSameAs(sameModel);
            assertThat(r.getStartDate()).isEqualTo(TODAY.plusDays(1));
            assertThat(r.getEndDate()).isEqualTo(TODAY.plusDays(3));
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        }

        @Test
        void refusesADifferentModel() {
            Reservation r = book(TODAY.plusDays(1), TODAY.plusDays(3));
            assertRule(() -> r.reassignTo(otherModel, TODAY, List.of()), DomainRuleViolation.INVALID_REQUEST);
            assertThat(r.getUnit()).isSameAs(unit);
        }

        @Test
        void refusesTheSameUnit() {
            Reservation r = book(TODAY.plusDays(1), TODAY.plusDays(3));
            assertRule(() -> r.reassignTo(unit, TODAY, List.of()), DomainRuleViolation.INVALID_REQUEST);
        }

        @Test
        void refusesAReplacementBookedOnAnyDayOfTheBooking() {
            Reservation r = book(TODAY.plusDays(1), TODAY.plusDays(3));
            Reservation bobOnReplacement = Reservation.create(bob, sameModel, TODAY.plusDays(3), TODAY.plusDays(4), TODAY, NOW);
            assertRule(() -> r.reassignTo(sameModel, TODAY, List.of(bobOnReplacement)), DomainRuleViolation.UNIT_UNAVAILABLE);
            assertThat(r.getUnit()).isSameAs(unit);
        }

        @Test
        void refusesAReplacementThatIsOverdueWithSomeoneElse() {
            // The spare's last booking ended yesterday, but Bob never brought it back.
            Reservation bobsOnSpare = Reservation.create(bob, sameModel, TODAY.minusDays(3), TODAY.minusDays(1), TODAY.minusDays(3), NOW);
            bobsOnSpare.pickUp(TODAY.minusDays(3), NOW, List.of(), List.of());
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertRule(() -> r.reassignTo(sameModel, TODAY, List.of(bobsOnSpare)), DomainRuleViolation.UNIT_UNAVAILABLE);
            assertThat(r.getUnit()).isSameAs(unit);
        }

        @Test
        void refusesAnOutOfServiceReplacement() {
            org.springframework.test.util.ReflectionTestUtils.setField(sameModel, "active", false);
            Reservation r = book(TODAY.plusDays(1), TODAY.plusDays(3));
            assertRule(() -> r.reassignTo(sameModel, TODAY, List.of()), DomainRuleViolation.UNIT_UNAVAILABLE);
        }

        @Test
        void onlyBookingsNotYetPickedUpCanMove() {
            Reservation r = book(TODAY, TODAY.plusDays(3));
            r.pickUp(TODAY, NOW, List.of(), List.of());
            assertRule(() -> r.reassignTo(sameModel, TODAY, List.of()), DomainRuleViolation.INVALID_RESERVATION_STATE);
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
            r.pickUp(TODAY, NOW, List.of(), List.of());
            assertThat(r.expireIfUnclaimed(TODAY.plusDays(30))).isFalse();
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
        }

        @Test
        void overdueOnlyAfterTheEndDateAndOnlyWhenPickedUp() {
            Reservation r = book(TODAY, TODAY.plusDays(2));
            assertThat(r.isOverdue(TODAY.plusDays(5))).isFalse(); // never picked up: not overdue
            r.pickUp(TODAY, NOW, List.of(), List.of());
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
