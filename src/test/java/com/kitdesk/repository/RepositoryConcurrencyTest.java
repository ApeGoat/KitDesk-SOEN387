package com.kitdesk.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.domain.CategoryCode;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.Reservation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Unit;
import com.kitdesk.domain.User;
import com.kitdesk.support.Concurrently;
import com.kitdesk.support.Concurrently.Outcome;
import com.kitdesk.support.PostgresTestContainer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The data source layer under real concurrency: many threads, each with its own transaction and
 * database connection, against PostgreSQL. Nothing here is rolled back automatically, so each
 * test commits for real and the reservations are deleted afterwards.
 *
 * <p>The "check then insert" steps below stand in for what the booking service will do; they
 * show which guarantees come from the locks and which from the database rule.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainer.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // each thread opens its own transaction
class RepositoryConcurrencyTest {

    static final LocalDate DAY0 = LocalDate.of(2026, 11, 2);
    static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    @Autowired UserRepository users;
    @Autowired UnitRepository units;
    @Autowired ReservationRepository reservations;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @AfterEach
    void deleteReservations() {
        jdbc.update("DELETE FROM reservations");
    }

    Long userId(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    String unitId(String assetTag) {
        return jdbc.queryForObject("SELECT public_id FROM units WHERE asset_tag = ?", String.class, assetTag);
    }

    List<Long> raceStudents(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ids.add(userId(String.format("student%02d@kitdesk.test", i)));
        }
        return ids;
    }

    long count(List<? extends Outcome<?>> outcomes, boolean succeeded) {
        return outcomes.stream().filter(o -> o.succeeded() == succeeded).count();
    }

    // ---------------------------------------------------------------------------------------
    // 1. Row locks really make other lockers wait
    // ---------------------------------------------------------------------------------------

    @Test
    void lockUnitMakesASecondLockerWaitUntilTheFirstCommits() throws Exception {
        String cam = unitId("CAM-R5-03");
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        long[] firstReleasedAt = new long[1];
        long[] secondGotLockAt = new long[1];

        Thread first = new Thread(() -> tx().executeWithoutResult(s -> {
            units.lockUnit(cam).orElseThrow();
            firstHoldsLock.countDown();
            sleep(800);
            firstReleasedAt[0] = System.nanoTime(); // commit happens right after this
        }));
        first.start();
        firstHoldsLock.await(10, TimeUnit.SECONDS);

        tx().executeWithoutResult(s -> {
            units.lockUnit(cam).orElseThrow();
            secondGotLockAt[0] = System.nanoTime();
        });
        first.join();

        assertThat(secondGotLockAt[0]).isGreaterThan(firstReleasedAt[0]);
    }

    @Test
    void lockStudentMakesASecondLockerWaitUntilTheFirstCommits() throws Exception {
        Long alice = userId("alice@kitdesk.test");
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        long[] firstReleasedAt = new long[1];
        long[] secondGotLockAt = new long[1];

        Thread first = new Thread(() -> tx().executeWithoutResult(s -> {
            users.lockStudent(alice).orElseThrow();
            firstHoldsLock.countDown();
            sleep(800);
            firstReleasedAt[0] = System.nanoTime();
        }));
        first.start();
        firstHoldsLock.await(10, TimeUnit.SECONDS);

        tx().executeWithoutResult(s -> {
            users.lockStudent(alice).orElseThrow();
            secondGotLockAt[0] = System.nanoTime();
        });
        first.join();

        assertThat(secondGotLockAt[0]).isGreaterThan(firstReleasedAt[0]);
    }

    @Test
    void aLockedUnitCanStillBeReadWithoutWaiting() throws Exception {
        // FOR UPDATE only blocks other lockers and writers. Browsing the catalogue isn't slowed down.
        String cam = unitId("CAM-R5-03");
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch readerDone = new CountDownLatch(1);

        Thread first = new Thread(() -> tx().executeWithoutResult(s -> {
            units.lockUnit(cam).orElseThrow();
            firstHoldsLock.countDown();
            await(readerDone);
        }));
        first.start();
        firstHoldsLock.await(10, TimeUnit.SECONDS);

        long start = System.nanoTime();
        assertThat(units.findByPublicId(cam)).isPresent();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        readerDone.countDown();
        first.join();

        assertThat(tookMs).isLessThan(500);
    }

    @Test
    void lockingAUnitDoesNotLockOtherUnitsOfTheSameModelOrCategory() throws Exception {
        // Regression test: an earlier version of lockUnit joined the model and category, and
        // PostgreSQL's FOR UPDATE then locked those rows too, so all lens bookings queued up.
        String first = unitId("LEN-2470-01");
        String sameModel = unitId("LEN-2470-02");
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread holder = new Thread(() -> tx().executeWithoutResult(s -> {
            units.lockUnit(first).orElseThrow().getModel().getCategory().getRentalLimit();
            firstHoldsLock.countDown();
            await(done);
        }));
        holder.start();
        firstHoldsLock.await(10, TimeUnit.SECONDS);

        long start = System.nanoTime();
        tx().executeWithoutResult(s -> units.lockUnit(sameModel).orElseThrow().getModel().getCategory().getRentalLimit());
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        done.countDown();
        holder.join();

        assertThat(tookMs).isLessThan(500);
    }

    @Test
    void lockingInOppositeOrdersDeadlocksWhichIsWhyTheServiceAlwaysLocksStudentFirst() throws Exception {
        // Thread A: student, then unit. Thread B: unit, then student. Each ends up waiting for the
        // other; PostgreSQL detects the cycle (after about 1 second) and aborts one of them.
        Long alice = userId("alice@kitdesk.test");
        String cam = unitId("CAM-R5-03");
        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);

        List<Callable<String>> tasks = List.of(
                () -> tx().execute(s -> {
                    users.lockStudent(alice).orElseThrow();
                    await(bothHoldFirstLock);
                    units.lockUnit(cam).orElseThrow();
                    return "A";
                }),
                () -> tx().execute(s -> {
                    units.lockUnit(cam).orElseThrow();
                    await(bothHoldFirstLock);
                    users.lockStudent(alice).orElseThrow();
                    return "B";
                }));
        List<Outcome<String>> outcomes = Concurrently.run(tasks);

        assertThat(count(outcomes, true)).isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .singleElement()
                .satisfies(o -> assertThat(o.error()).isInstanceOf(ConcurrencyFailureException.class));
    }

    @Test
    void lockingInTheSameOrderNeverDeadlocks() throws Exception {
        Long alice = userId("alice@kitdesk.test");
        String cam = unitId("CAM-R5-03");
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> tx().execute(s -> {
                users.lockStudent(alice).orElseThrow();
                units.lockUnit(cam).orElseThrow();
                return "ok";
            }));
        }
        assertThat(count(Concurrently.run(tasks), true)).isEqualTo(8);
    }

    // ---------------------------------------------------------------------------------------
    // 2. Double-booking one unit: 20 students try to book the same unit for the same days
    // ---------------------------------------------------------------------------------------

    /** What the booking service will do for the unit: lock, check availability, insert. */
    Callable<String> lockedBooking(Long studentId, String unitPublicId) {
        return () -> tx().execute(s -> {
            User student = users.lockStudent(studentId).orElseThrow();
            Unit unit = units.lockUnit(unitPublicId).orElseThrow();
            unit.assertAvailable(DAY0, DAY0.plusDays(2), reservations.findActiveByUnit(unit));
            return reservations.save(Reservation.create(student, unit, DAY0, DAY0.plusDays(2), DAY0, NOW)).getPublicId();
        });
    }

    @Test
    void withLocksExactlyOneOfTwentyBookingsWinsAndTheRestFailTheAvailabilityCheck() throws Exception {
        String cam = unitId("CAM-R5-03");
        List<Callable<String>> tasks = new ArrayList<>();
        for (Long student : raceStudents(20)) {
            tasks.add(lockedBooking(student, cam));
        }
        List<Outcome<String>> outcomes = Concurrently.run(tasks);

        assertThat(count(outcomes, true)).isEqualTo(1);
        // With the unit lock, every loser is stopped by the app's own check, never by the backstop.
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).hasSize(19).allSatisfy(o -> {
            assertThat(o.error()).isInstanceOf(DomainRuleViolation.class);
            assertThat(((DomainRuleViolation) o.error()).getCode()).isEqualTo(DomainRuleViolation.UNIT_UNAVAILABLE);
        });
        assertThat(activeCount(cam)).isEqualTo(1);
    }

    @Test
    void withoutLocksTheDatabaseRuleStillStopsTheDoubleBooking() throws Exception {
        // Simulates the locks failing: every thread reads availability, all wait at a barrier so
        // they all see "free", then all insert. The exclusion constraint is the only thing left.
        String cam = unitId("CAM-R5-03");
        List<Long> students = raceStudents(8); // at most the connection pool size, so all reach the barrier
        CyclicBarrier allCheckedAvailability = new CyclicBarrier(students.size());

        List<Callable<String>> tasks = new ArrayList<>();
        for (Long studentId : students) {
            tasks.add(() -> tx().execute(s -> {
                User student = users.findById(studentId).orElseThrow();      // no lock
                Unit unit = units.findByPublicId(cam).orElseThrow();          // no lock
                unit.assertAvailable(DAY0, DAY0.plusDays(2), reservations.findActiveByUnit(unit));
                await(allCheckedAvailability);                                 // everyone saw "free"
                return reservations.saveAndFlush(
                        Reservation.create(student, unit, DAY0, DAY0.plusDays(2), DAY0, NOW)).getPublicId();
            }));
        }
        List<Outcome<String>> outcomes = Concurrently.run(tasks);

        assertThat(count(outcomes, true)).isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).hasSize(7)
                .allSatisfy(o -> assertThat(DatabaseConstraints.isReservationOverlap(o.error())).isTrue());
        assertThat(activeCount(cam)).isEqualTo(1);
    }

    @Test
    void differentUnitsDoNotBlockEachOther() throws Exception {
        List<Long> students = raceStudents(3);
        List<Callable<String>> tasks = List.of(
                lockedBooking(students.get(0), unitId("CAM-R5-01")),
                lockedBooking(students.get(1), unitId("CAM-R5-02")),
                lockedBooking(students.get(2), unitId("CAM-R5-03")));
        assertThat(count(Concurrently.run(tasks), true)).isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------
    // 3. Category limit: one student fires five lens bookings at once (limit is 2)
    // ---------------------------------------------------------------------------------------

    static final List<String> LENSES = List.of("LEN-2470-01", "LEN-2470-02", "LEN-50-01", "LEN-50-02", "LEN-50-03");

    @Test
    void withTheStudentLockTheCategoryLimitHolds() throws Exception {
        Long alice = userId("alice@kitdesk.test");
        Long lensCategory = categories.findByCode(CategoryCode.LENS).orElseThrow().getId();

        List<Callable<String>> tasks = new ArrayList<>();
        for (String lens : LENSES) {
            String lensId = unitId(lens);
            tasks.add(() -> tx().execute(s -> {
                User student = users.lockStudent(alice).orElseThrow();
                Unit unit = units.lockUnit(lensId).orElseThrow();
                long held = reservations.countActiveInCategory(alice, lensCategory);
                sleep(50); // widen the gap between check and insert; the lock must still hold
                if (held >= unit.getModel().getCategory().getRentalLimit()) {
                    throw new IllegalStateException("CATEGORY_LIMIT_REACHED");
                }
                return reservations.save(Reservation.create(student, unit, DAY0, DAY0.plusDays(2), DAY0, NOW)).getPublicId();
            }));
        }
        List<Outcome<String>> outcomes = Concurrently.run(tasks);

        assertThat(count(outcomes, true)).isEqualTo(2);
        assertThat(reservations.countActiveInCategory(alice, lensCategory)).isEqualTo(2);
    }

    @Test
    void withoutTheStudentLockTheCategoryLimitIsBroken() throws Exception {
        // The failure the student lock exists to prevent. Each booking is for a different lens, so
        // the unit lock and the exclusion constraint can't help: nothing in the database knows
        // about the limit. All five threads count 0 before any of them inserts.
        Long alice = userId("alice@kitdesk.test");
        Long lensCategory = categories.findByCode(CategoryCode.LENS).orElseThrow().getId();
        CyclicBarrier allCounted = new CyclicBarrier(LENSES.size());

        List<Callable<String>> tasks = new ArrayList<>();
        for (String lens : LENSES) {
            String lensId = unitId(lens);
            tasks.add(() -> tx().execute(s -> {
                User student = users.findById(alice).orElseThrow();          // no student lock
                Unit unit = units.lockUnit(lensId).orElseThrow();
                long held = reservations.countActiveInCategory(alice, lensCategory);
                await(allCounted);
                if (held >= unit.getModel().getCategory().getRentalLimit()) {
                    throw new IllegalStateException("CATEGORY_LIMIT_REACHED");
                }
                return reservations.save(Reservation.create(student, unit, DAY0, DAY0.plusDays(2), DAY0, NOW)).getPublicId();
            }));
        }
        Concurrently.run(tasks);

        assertThat(reservations.countActiveInCategory(alice, lensCategory))
                .as("limit is 2, but without the student lock all 5 got through")
                .isEqualTo(5);
    }

    // ---------------------------------------------------------------------------------------
    // 4. Optimistic locking on status changes
    // ---------------------------------------------------------------------------------------

    @Test
    void twoConcurrentStatusChangesCannotBothCommit() throws Exception {
        // Staff tablet records the pickup while the student cancels on their phone. Both read
        // version 0; whichever commits second is refused by @Version.
        Long alice = userId("alice@kitdesk.test");
        String cam = unitId("CAM-R5-03");
        String resId = tx().execute(s -> reservations.save(Reservation.create(
                users.findById(alice).orElseThrow(), units.findByPublicId(cam).orElseThrow(),
                DAY0, DAY0.plusDays(2), DAY0, NOW)).getPublicId());
        CyclicBarrier bothRead = new CyclicBarrier(2);

        List<Callable<String>> tasks = List.of(
                () -> tx().execute(s -> {
                    Reservation r = reservations.findByPublicIdAtVersion(resId, 0).orElseThrow();
                    await(bothRead);
                    r.cancel(NOW);
                    reservations.saveAndFlush(r);
                    return "cancel";
                }),
                () -> tx().execute(s -> {
                    Reservation r = reservations.findByPublicIdAtVersion(resId, 0).orElseThrow();
                    await(bothRead);
                    r.pickUp(DAY0, NOW);
                    reservations.saveAndFlush(r);
                    return "pickup";
                }));
        List<Outcome<String>> outcomes = Concurrently.run(tasks);

        assertThat(count(outcomes, true)).isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).singleElement()
                .satisfies(o -> assertThat(o.error()).isInstanceOfAny(
                        ObjectOptimisticLockingFailureException.class, ConcurrencyFailureException.class));

        Reservation after = reservations.findByPublicId(resId).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(1);
        String winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
        assertThat(after.getStatus())
                .isEqualTo(winner.equals("cancel") ? ReservationStatus.CANCELLED : ReservationStatus.PICKED_UP);
    }

    @Test
    void aClientHoldingAnOldVersionIsRefusedBeforeAnythingChanges() {
        Long alice = userId("alice@kitdesk.test");
        String cam = unitId("CAM-R5-03");
        String resId = tx().execute(s -> reservations.save(Reservation.create(
                users.findById(alice).orElseThrow(), units.findByPublicId(cam).orElseThrow(),
                DAY0, DAY0.plusDays(2), DAY0, NOW)).getPublicId());

        // The tablet read version 0, then the student cancelled (version 1).
        tx().executeWithoutResult(s -> reservations.findByPublicIdAtVersion(resId, 0).orElseThrow().cancel(NOW));

        // The tablet now tries a pickup with its stale version 0.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                        reservations.findByPublicIdAtVersion(resId, 0).orElseThrow().pickUp(DAY0, NOW)))
                .isInstanceOf(VersionMismatchException.class);
        assertThat(reservations.findByPublicId(resId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    // ---------------------------------------------------------------------------------------

    long activeCount(String unitPublicId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN units u ON u.id = r.unit_id
                WHERE u.public_id = ? AND r.status IN ('RESERVED', 'PICKED_UP')""", Long.class, unitPublicId);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("threads never all reached the barrier", e);
        }
    }
}
