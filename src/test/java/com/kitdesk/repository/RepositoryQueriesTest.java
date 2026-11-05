package com.kitdesk.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kitdesk.domain.Category;
import com.kitdesk.domain.CategoryCode;
import com.kitdesk.domain.EquipmentModel;
import com.kitdesk.domain.IdempotencyRecord;
import com.kitdesk.domain.Reservation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Unit;
import com.kitdesk.domain.User;
import com.kitdesk.support.PostgresTestContainer;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Normal behaviour of the data source layer against the real schema and seed data. Each test runs
 * in a transaction that is rolled back afterwards, so tests don't see each other's rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainer.class)
class RepositoryQueriesTest {

    // Reservation.create refuses a start date before "today", so tests pass their own today.
    static final LocalDate DAY0 = LocalDate.of(2026, 11, 2);
    static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired EquipmentModelRepository models;
    @Autowired UnitRepository units;
    @Autowired ReservationRepository reservations;
    @Autowired IdempotencyRepository idempotency;
    @Autowired EntityManager em;

    User user(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow();
    }

    Unit unit(String assetTag) {
        return units.findAll().stream().filter(u -> u.getAssetTag().equals(assetTag)).findFirst().orElseThrow();
    }

    Reservation book(User student, Unit unit, int fromDay, int toDay) {
        return reservations.saveAndFlush(
                Reservation.create(student, unit, DAY0.plusDays(fromDay), DAY0.plusDays(toDay), DAY0, NOW));
    }

    /** Push pending changes and forget cached entities, so the next read really hits the database. */
    void flushAndClear() {
        em.flush();
        em.clear();
    }

    // ---- catalogue ----

    @Test
    void seededCategoriesCarryTheirRentalLimits() {
        List<Category> all = categories.findAllByOrderByIdAsc();
        assertThat(all).extracting(Category::getCode)
                .containsExactly(CategoryCode.CAMERA, CategoryCode.LENS, CategoryCode.LIGHTING, CategoryCode.SOUND);
        assertThat(all).extracting(Category::getRentalLimit).containsExactly(1, 2, 1, 2);
        assertThat(all).allSatisfy(c -> assertThat(c.getPublicId()).matches("cat_[0-9a-f]{16}"));
    }

    @Test
    void modelsCanBeFilteredByCategory() {
        assertThat(models.findByCategoryCode(CategoryCode.LENS))
                .extracting(EquipmentModel::getDisplayName)
                .containsExactly("Canon RF 24-70mm f/2.8", "Canon RF 50mm f/1.8");
        assertThat(models.findAllWithCategory()).hasSize(7);
    }

    @Test
    void unitCountAndUnitsPerModel() {
        EquipmentModel r5 = models.findByCategoryCode(CategoryCode.CAMERA).get(0);
        assertThat(models.countUnits(r5)).isEqualTo(3);
        assertThat(units.findByModelOrderByAssetTagAsc(r5)).extracting(Unit::getAssetTag)
                .containsExactly("CAM-R5-01", "CAM-R5-02", "CAM-R5-03");
    }

    @Test
    void unitsAreFoundByPublicIdNotByAssetTag() {
        Unit u = unit("CAM-R5-03");
        assertThat(units.findByPublicId(u.getPublicId())).contains(u);
        assertThat(units.findByPublicId("CAM-R5-03")).isEmpty();
        assertThat(units.findByPublicId("unt_doesnotexist0000")).isEmpty();
    }

    @Test
    void userLookupIgnoresEmailCase() {
        assertThat(users.findByEmailIgnoreCase("ALICE@kitdesk.test")).isPresent();
        assertThat(users.findByEmailIgnoreCase("nobody@kitdesk.test")).isEmpty();
    }

    // ---- reservations ----

    @Test
    void savedReservationKeepsItsPublicIdAndStartsAtVersionZero() {
        Reservation r = book(user("alice@kitdesk.test"), unit("CAM-R5-01"), 0, 2);
        flushAndClear();

        Reservation loaded = reservations.findByPublicId(r.getPublicId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getUnit().getModel().getCategory().getCode()).isEqualTo(CategoryCode.CAMERA);
    }

    @Test
    void everyStateChangeBumpsTheVersion() {
        Reservation r = book(user("alice@kitdesk.test"), unit("CAM-R5-01"), 0, 2);
        r.pickUp(DAY0, NOW, List.of());
        reservations.saveAndFlush(r);
        r.markReturned(NOW);
        reservations.saveAndFlush(r);
        flushAndClear();

        assertThat(reservations.findByPublicId(r.getPublicId()).orElseThrow().getVersion()).isEqualTo(2);
    }

    @Test
    void earlyPickupSavesTheMovedStartDate() {
        Unit cam = unit("CAM-R5-01");
        Reservation r = book(user("alice@kitdesk.test"), cam, 3, 5);
        r.pickUp(DAY0, NOW, reservations.findActiveByUnit(cam));
        reservations.saveAndFlush(r);
        flushAndClear();

        Reservation loaded = reservations.findByPublicId(r.getPublicId()).orElseThrow();
        assertThat(loaded.getStartDate()).isEqualTo(DAY0);
        assertThat(loaded.getEndDate()).isEqualTo(DAY0.plusDays(5));
        assertThat(loaded.getStatus()).isEqualTo(ReservationStatus.PICKED_UP);
    }

    @Test
    void blockedBookingsAreThoseWhoseUnitIsStillOutPastItsEndDate() {
        // Bob had CAM-R5-03 for days 0..2 and hasn't brought it back. Alice booked it from day 4.
        // On day 4 her booking is blocked; Carol's booking of another camera isn't.
        User bob = user("bob@kitdesk.test");
        Unit cam3 = unit("CAM-R5-03");
        Reservation bobs = book(bob, cam3, 0, 2);
        bobs.pickUp(DAY0, NOW, java.util.List.of());
        Reservation alices = book(user("alice@kitdesk.test"), cam3, 4, 6);
        book(user("carol@kitdesk.test"), unit("CAM-R5-01"), 4, 6);
        flushAndClear();

        LocalDate day4 = DAY0.plusDays(4);
        assertThat(reservations.findBlockedByOverdueUnit(day4, day4))
                .extracting(Reservation::getPublicId).containsExactly(alices.getPublicId());
        // Before Bob is late (day 2 is his last day), nothing is blocked yet.
        assertThat(reservations.findBlockedByOverdueUnit(DAY0.plusDays(2), DAY0.plusDays(6))).isEmpty();
        // Looking ahead: on day 3 Bob is late, and Alice's day-4 start is inside a one-day horizon.
        assertThat(reservations.findBlockedByOverdueUnit(DAY0.plusDays(3), DAY0.plusDays(4))).hasSize(1);
    }

    @Test
    void replacementCandidatesAreOtherInServiceUnitsOfTheSameModel() {
        Unit cam3 = unit("CAM-R5-03");
        assertThat(units.findReplacementCandidates(cam3.getModel(), cam3.getId()))
                .extracting(Unit::getAssetTag).containsExactly("CAM-R5-01", "CAM-R5-02");
    }

    @Test
    void reassignedBookingIsSavedOnTheNewUnit() {
        Unit cam3 = unit("CAM-R5-03");
        Unit cam1 = unit("CAM-R5-01");
        Reservation r = book(user("alice@kitdesk.test"), cam3, 4, 6);
        r.reassignTo(cam1, reservations.findActiveByUnit(cam1));
        reservations.saveAndFlush(r);
        flushAndClear();

        Reservation loaded = reservations.findByPublicId(r.getPublicId()).orElseThrow();
        assertThat(loaded.getUnit().getAssetTag()).isEqualTo("CAM-R5-01");
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void findAtVersionReturnsTheReservationWhenTheVersionMatches() {
        Reservation r = book(user("alice@kitdesk.test"), unit("CAM-R5-01"), 0, 2);
        assertThat(reservations.findByPublicIdAtVersion(r.getPublicId(), 0)).isPresent();
    }

    @Test
    void findAtVersionRefusesAStaleVersion() {
        Reservation r = book(user("alice@kitdesk.test"), unit("CAM-R5-01"), 0, 2);
        r.cancel(NOW);
        reservations.saveAndFlush(r); // now at version 1
        flushAndClear();

        assertThatThrownBy(() -> reservations.findByPublicIdAtVersion(r.getPublicId(), 0))
                .isInstanceOf(VersionMismatchException.class)
                .hasMessageContaining("version 1, not 0");
    }

    @Test
    void findAtVersionOfAnUnknownReservationIsEmpty() {
        assertThat(reservations.findByPublicIdAtVersion("res_doesnotexist0000", 0)).isEmpty();
    }

    @Test
    void activeByUnitIgnoresFinishedReservations() {
        User alice = user("alice@kitdesk.test");
        Unit cam = unit("CAM-R5-01");
        Reservation live = book(alice, cam, 0, 1);
        Reservation cancelled = book(alice, cam, 2, 3);
        cancelled.cancel(NOW);
        Reservation later = book(alice, cam, 4, 5);
        flushAndClear();

        assertThat(reservations.findActiveByUnit(units.findByPublicId(cam.getPublicId()).orElseThrow()))
                .extracting(Reservation::getPublicId)
                .containsExactly(live.getPublicId(), later.getPublicId());
    }

    @Test
    void categoryCountOnlyCountsThisStudentsActiveRentalsInThatCategory() {
        User alice = user("alice@kitdesk.test");
        User bob = user("bob@kitdesk.test");
        book(alice, unit("LEN-50-01"), 0, 1);                 // lens, reserved: counts
        Reservation picked = book(alice, unit("LEN-50-02"), 0, 1);
        picked.pickUp(DAY0, NOW, List.of());                              // lens, picked up: counts
        Reservation gone = book(alice, unit("LEN-50-03"), 0, 1);
        gone.cancel(NOW);                                      // lens, cancelled: doesn't
        book(alice, unit("CAM-R5-01"), 0, 1);                 // other category: doesn't
        book(bob, unit("LEN-2470-01"), 0, 1);                 // other student: doesn't
        flushAndClear();

        Long lensId = categories.findByCode(CategoryCode.LENS).orElseThrow().getId();
        assertThat(reservations.countActiveInCategory(alice.getId(), lensId)).isEqualTo(2);
        assertThat(reservations.countActiveInCategory(bob.getId(), lensId)).isEqualTo(1);
    }

    @Test
    void overdueMeansPickedUpAndPastTheEndDate() {
        User alice = user("alice@kitdesk.test");
        User bob = user("bob@kitdesk.test");
        Reservation alicesCamera = book(alice, unit("CAM-R5-01"), 0, 2);
        alicesCamera.pickUp(DAY0, NOW, List.of());
        book(bob, unit("CAM-R5-02"), 0, 2); // never picked up: not overdue, even later
        flushAndClear();

        LocalDate lastDay = DAY0.plusDays(2);
        assertThat(reservations.existsOverdue(alice.getId(), lastDay)).isFalse();
        assertThat(reservations.existsOverdue(alice.getId(), lastDay.plusDays(1))).isTrue();
        assertThat(reservations.existsOverdue(bob.getId(), lastDay.plusDays(1))).isFalse();
    }

    @Test
    void unclaimedPastEndFindsOnlyReservedBookingsWhoseEndHasPassed() {
        User alice = user("alice@kitdesk.test");
        Reservation unclaimed = book(alice, unit("CAM-R5-01"), 0, 1);
        Reservation pickedUp = book(alice, unit("CAM-R5-02"), 0, 1);
        pickedUp.pickUp(DAY0, NOW, List.of());
        book(alice, unit("CAM-R5-03"), 0, 5); // still running
        flushAndClear();

        assertThat(reservations.findUnclaimedPastEnd(DAY0.plusDays(2)))
                .extracting(Reservation::getPublicId)
                .containsExactly(unclaimed.getPublicId());
    }

    @Test
    void databaseRefusesAnOverlappingActiveReservationEvenWithoutAnyAppCheck() {
        Unit cam = unit("CAM-R5-03");
        book(user("alice@kitdesk.test"), cam, 0, 4);

        assertThatThrownBy(() -> book(user("bob@kitdesk.test"), cam, 4, 6))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(DatabaseConstraints.isReservationOverlap(e)).isTrue());
    }

    @Test
    void otherIntegrityErrorsAreNotMistakenForAnOverlap() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_email_key\"");
        assertThat(DatabaseConstraints.isReservationOverlap(other)).isFalse();
    }

    // ---- idempotency keys ----

    @Test
    void idempotencyKeysAreScopedPerUser() {
        Long alice = user("alice@kitdesk.test").getId();
        Long bob = user("bob@kitdesk.test").getId();
        idempotency.saveAndFlush(new IdempotencyRecord(alice, "key-1", "h1", 201, "{\"id\":\"res_a\"}", NOW));
        idempotency.saveAndFlush(new IdempotencyRecord(bob, "key-1", "h2", 201, "{\"id\":\"res_b\"}", NOW));
        flushAndClear();

        assertThat(idempotency.findByUserIdAndKey(alice, "key-1").orElseThrow().getResponseBody()).contains("res_a");
        assertThat(idempotency.findByUserIdAndKey(bob, "key-1").orElseThrow().getResponseBody()).contains("res_b");
        assertThat(idempotency.findByUserIdAndKey(alice, "key-2")).isEmpty();
    }

    @Test
    void aUserCannotStoreTheSameKeyTwice() {
        Long alice = user("alice@kitdesk.test").getId();
        idempotency.saveAndFlush(new IdempotencyRecord(alice, "key-1", "h1", 201, "{}", NOW));
        em.clear();
        assertThatThrownBy(() -> {
            em.persist(new IdempotencyRecord(alice, "key-1", "h1", 201, "{}", NOW));
            em.flush();
        }).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    @Test
    void oldKeysAreForgottenAfterTheRetentionWindow() {
        Long alice = user("alice@kitdesk.test").getId();
        idempotency.saveAndFlush(new IdempotencyRecord(alice, "old", "h", 201, "{}", NOW.minus(25, ChronoUnit.HOURS)));
        idempotency.saveAndFlush(new IdempotencyRecord(alice, "fresh", "h", 201, "{}", NOW.minus(1, ChronoUnit.HOURS)));

        assertThat(idempotency.deleteOlderThan(NOW.minus(24, ChronoUnit.HOURS))).isEqualTo(1);
        flushAndClear();
        assertThat(idempotency.findByUserIdAndKey(alice, "old")).isEmpty();
        assertThat(idempotency.findByUserIdAndKey(alice, "fresh")).isPresent();
    }
}
