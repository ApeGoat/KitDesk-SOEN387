package com.kitdesk.service;

import com.kitdesk.domain.Category;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.Reservation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Role;
import com.kitdesk.domain.Unit;
import com.kitdesk.domain.User;
import com.kitdesk.repository.DatabaseConstraints;
import com.kitdesk.repository.ReservationRepository;
import com.kitdesk.repository.RowLocks;
import com.kitdesk.repository.UserRepository;
import com.kitdesk.service.Views.ReservationView;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booking use cases (§5.6): book, list, get, cancel, pick up, return.
 *
 * <p>Each write is one transaction, opened here. This class owns the rules that look across
 * many reservations (category limit, subscription, overdue lock-out), the per-user rules (a
 * student only sees and cancels their own bookings; pickup and return are staff-only), and the
 * lock order: <b>student row first, then unit row</b>, always. Rules about one reservation's own
 * data (date window, status changes, early pickup) are the entities' job; this class calls them.
 */
@Service
public class ReservationService {

    /** What a student asks for when booking. Parsed and shape-checked by the interface layer. */
    public record NewBooking(String unitId, LocalDate startDate, LocalDate endDate) {
    }

    private final ReservationRepository reservations;
    private final UserRepository users;
    private final RowLocks locks;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, UserRepository users, RowLocks locks,
                              Clock clock) {
        this.reservations = reservations;
        this.users = users;
        this.locks = locks;
        this.clock = clock;
    }

    /** Students get their own reservations; staff get everyone's. Both can filter. */
    @Transactional(readOnly = true)
    public List<ReservationView> list(Caller caller, ReservationStatus status, Boolean overdue) {
        LocalDate today = today();
        List<Reservation> found = caller.isStaff()
                ? reservations.findAllNewestFirst()
                : reservations.findByStudent(users.getReferenceById(caller.userId()));
        return found.stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> overdue == null || r.isOverdue(today) == overdue)
                .map(r -> ReservationView.of(r, today))
                .toList();
    }

    /** A student asking for someone else's reservation gets NOT_FOUND, as if it didn't exist. */
    @Transactional(readOnly = true)
    public ReservationView get(Caller caller, String reservationId) {
        return ReservationView.of(findVisible(caller, reservationId), today());
    }

    /**
     * Books a unit for the calling student. Checks, in order: role, dates, then (holding the
     * student's lock) subscription and overdue gear, then (holding the unit's lock) the category
     * limit and the unit's calendar. The database's exclusion constraint is the last line of
     * defence if two overlapping bookings ever got this far at once.
     */
    @Transactional
    public ReservationView create(Caller caller, NewBooking booking) {
        Access.require(caller, Role.STUDENT);
        LocalDate today = today();
        if (booking.unitId() == null || booking.unitId().isBlank()) {
            throw new DomainRuleViolation(ErrorCatalog.INVALID_REQUEST, "unitId is required.");
        }
        Reservation.checkWindow(booking.startDate(), booking.endDate(), today);

        // 1. Lock the student: this student's bookings now run one at a time (category limit).
        User student = locks.lockStudent(caller.userId()).orElseThrow(
                () -> new DomainRuleViolation(ErrorCatalog.UNAUTHORIZED, "This account no longer exists."));
        if (!student.isSubscriptionActive()) {
            throw new DomainRuleViolation(ErrorCatalog.SUBSCRIPTION_INACTIVE,
                    "Your subscription is not active, so you can't book gear.");
        }
        if (reservations.existsOverdue(student.getId(), today)) {
            throw new DomainRuleViolation(ErrorCatalog.OVERDUE_GEAR,
                    "You have overdue gear. Return it before booking anything else.");
        }

        // 2. Lock the unit: bookings of this unit now run one at a time (no double-booking).
        Unit unit = locks.lockUnit(booking.unitId()).orElseThrow(
                () -> new DomainRuleViolation(ErrorCatalog.NOT_FOUND, "No unit has the id " + booking.unitId() + "."));
        Category category = unit.getModel().getCategory();
        if (reservations.countActiveInCategory(student.getId(), category.getId()) >= category.getRentalLimit()) {
            throw new DomainRuleViolation(ErrorCatalog.CATEGORY_LIMIT_REACHED,
                    "You already hold " + category.getRentalLimit() + " active " + category.getName().toLowerCase()
                            + " rental(s), the most allowed at once.");
        }
        unit.assertAvailable(booking.startDate(), booking.endDate(), reservations.findActiveByUnit(unit));

        Reservation reservation = Reservation.create(student, unit, booking.startDate(), booking.endDate(),
                today, clock.instant());
        save(reservation);
        return ReservationView.of(reservation, today);
    }

    /** The student cancels their own RESERVED booking. Staff don't cancel (the contract has no such case). */
    @Transactional
    public ReservationView cancel(Caller caller, String reservationId, Integer expectedVersion) {
        int version = requireVersion(expectedVersion);
        Reservation reservation = findOwnedBy(caller, reservationId);
        reservations.findByPublicIdAtVersion(reservationId, version);
        reservation.cancel(clock.instant());
        save(reservation);
        return ReservationView.of(reservation, today());
    }

    /**
     * Staff hand the unit over. The student must have no overdue gear. Takes the same locks in the
     * same order as booking (student, then unit), because an early pickup moves the start date
     * and must not race a new booking of the days in between.
     */
    @Transactional
    public ReservationView pickUp(Caller caller, String reservationId, Integer expectedVersion) {
        Access.require(caller, Role.STAFF);
        int version = requireVersion(expectedVersion);
        Reservation reservation = findOrNotFound(reservationId);
        reservations.findByPublicIdAtVersion(reservationId, version);
        LocalDate today = today();

        User student = locks.lockStudent(reservation.getStudent().getId()).orElseThrow();
        if (reservations.existsOverdue(student.getId(), today)) {
            throw new DomainRuleViolation(ErrorCatalog.OVERDUE_GEAR,
                    "This student has overdue gear, so they can't pick anything up until it's returned.");
        }
        Unit unit = locks.lockUnit(reservation.getUnit().getPublicId()).orElseThrow();
        reservation.pickUp(today, clock.instant(), reservations.findActiveByUnit(unit));
        save(reservation);
        return ReservationView.of(reservation, today);
    }

    /** Staff take the unit back, overdue or not. */
    @Transactional
    public ReservationView markReturned(Caller caller, String reservationId, Integer expectedVersion) {
        Access.require(caller, Role.STAFF);
        int version = requireVersion(expectedVersion);
        Reservation reservation = findOrNotFound(reservationId);
        reservations.findByPublicIdAtVersion(reservationId, version);
        reservation.markReturned(clock.instant());
        save(reservation);
        return ReservationView.of(reservation, today());
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Writes now (not at commit) so a refusal from the database comes back as the right error:
     * the overlap constraint as UNIT_UNAVAILABLE, a concurrent change (@Version) as a version
     * mismatch.
     */
    private void save(Reservation reservation) {
        try {
            reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            if (DatabaseConstraints.isReservationOverlap(e)) {
                throw new DomainRuleViolation(ErrorCatalog.UNIT_UNAVAILABLE,
                        reservation.getUnit().getAssetTag() + " was just booked for some of these days.");
            }
            throw e;
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new DomainRuleViolation(ErrorCatalog.RESERVATION_VERSION_MISMATCH,
                    "Reservation " + reservation.getPublicId() + " was changed by someone else. Re-read it and try again.");
        }
    }

    private static int requireVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new DomainRuleViolation(ErrorCatalog.VERSION_REQUIRED,
                    "The If-Match-Version header is required. Read the reservation and send its version.");
        }
        return expectedVersion;
    }

    private Reservation findOrNotFound(String reservationId) {
        return reservations.findByPublicId(reservationId).orElseThrow(() -> notFound(reservationId));
    }

    private Reservation findVisible(Caller caller, String reservationId) {
        Reservation r = findOrNotFound(reservationId);
        if (caller.isStaff() || r.getStudent().getId().equals(caller.userId())) {
            return r;
        }
        throw notFound(reservationId);
    }

    private Reservation findOwnedBy(Caller caller, String reservationId) {
        Reservation r = findOrNotFound(reservationId);
        if (caller.isStudent() && r.getStudent().getId().equals(caller.userId())) {
            return r;
        }
        throw notFound(reservationId);
    }

    private static DomainRuleViolation notFound(String reservationId) {
        return new DomainRuleViolation(ErrorCatalog.NOT_FOUND, "No reservation has the id " + reservationId + ".");
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

}
