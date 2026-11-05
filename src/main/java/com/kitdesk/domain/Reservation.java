package com.kitdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;

/**
 * A student's booking of one unit for an inclusive date range.
 *
 * <p>This class enforces the rules that only need the reservation's own data (§5.5): the
 * 10-day window and the status transitions. Rules that look across many reservations (category
 * limit, overdue lock-out, subscription) live in the service layer.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    /** A reservation covers at most this many days, both ends included. */
    public static final int MAX_DAYS = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private User student;

    /** Can change, but only through {@link #reassignTo} (same model, unit free for the whole booking). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;

    /** Can move earlier, but only through an early pickup (see {@link #pickUp}). */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Optimistic-locking version. Hibernate adds "WHERE version = ?" to every UPDATE and bumps it,
     * so two transactions that both changed the same reservation can't both commit.
     */
    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reservation() {
        // for JPA
    }

    private Reservation(User student, Unit unit, LocalDate startDate, LocalDate endDate, Instant now) {
        this.publicId = PublicIds.newId("res");
        this.student = student;
        this.unit = unit;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = now;
    }

    /**
     * Creates a new RESERVED reservation after checking the date window: end on or after start, at
     * most {@value #MAX_DAYS} days (end no later than start + 9), and start not before today.
     * {@code today} and {@code now} are parameters so tests can fix the clock.
     *
     * <p>Does not check availability or eligibility: availability is {@link Unit#assertAvailable},
     * and limits, subscription and overdue gear are the service's job.
     *
     * @throws DomainRuleViolation INVALID_REQUEST for a bad window
     */
    public static Reservation create(User student, Unit unit, LocalDate startDate, LocalDate endDate,
                                     LocalDate today, Instant now) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(unit, "unit");
        if (startDate == null || endDate == null) {
            throw invalid("startDate and endDate are required.");
        }
        if (endDate.isBefore(startDate)) {
            throw invalid("endDate must be on or after startDate.");
        }
        if (endDate.isAfter(startDate.plusDays(MAX_DAYS - 1))) {
            throw invalid("A reservation covers at most " + MAX_DAYS + " days; the latest endDate for "
                    + startDate + " is " + startDate.plusDays(MAX_DAYS - 1) + ".");
        }
        if (startDate.isBefore(today)) {
            throw invalid("startDate must not be before today (" + today + ").");
        }
        return new Reservation(student, unit, startDate, endDate, now);
    }

    /** Student cancels before pickup. Only a RESERVED reservation can be cancelled. */
    public void cancel(Instant now) {
        requireStatus(ReservationStatus.RESERVED, "cancelled");
        status = ReservationStatus.CANCELLED;
        cancelledAt = now;
    }

    /**
     * Staff hand the unit over. Only a RESERVED reservation, and not after its endDate (by then
     * the booking has lapsed).
     *
     * <p><b>Early pickup</b> (today is before startDate) is allowed only if all of these hold:
     * <ul>
     *   <li>the unit is back at the desk: no other reservation of it is PICKED_UP;</li>
     *   <li>no other active reservation of the unit falls on any day from today up to the day
     *       before startDate;</li>
     *   <li>the booking would still cover at most {@value #MAX_DAYS} days.</li>
     * </ul>
     * Then startDate moves to today; endDate never changes. {@code unitBookings} is the unit's
     * active reservations, loaded by the data source layer while the unit row is locked.
     */
    public void pickUp(LocalDate today, Instant now, Collection<Reservation> unitBookings) {
        requireStatus(ReservationStatus.RESERVED, "picked up");
        if (today.isAfter(endDate)) {
            throw new DomainRuleViolation(DomainRuleViolation.INVALID_RESERVATION_STATE,
                    "This reservation ended on " + endDate + " and was never picked up.");
        }
        if (today.isBefore(startDate)) {
            moveStartEarlierFor(today, unitBookings);
        }
        status = ReservationStatus.PICKED_UP;
        pickedUpAt = now;
    }

    private void moveStartEarlierFor(LocalDate today, Collection<Reservation> unitBookings) {
        if (endDate.isAfter(today.plusDays(MAX_DAYS - 1))) {
            throw new DomainRuleViolation(DomainRuleViolation.INVALID_RESERVATION_STATE,
                    "Picking up on " + today + " would make this booking longer than " + MAX_DAYS
                            + " days; the earliest pickup is " + endDate.minusDays(MAX_DAYS - 1) + ".");
        }
        LocalDate dayBeforeStart = startDate.minusDays(1);
        for (Reservation other : unitBookings) {
            if (other == this || other.publicId.equals(publicId) || !other.isActive()) {
                continue;
            }
            if (other.status == ReservationStatus.PICKED_UP) {
                throw new DomainRuleViolation(DomainRuleViolation.UNIT_UNAVAILABLE,
                        "Early pickup refused: " + unit.getAssetTag() + " has not been returned yet.");
            }
            if (other.overlaps(today, dayBeforeStart)) {
                throw new DomainRuleViolation(DomainRuleViolation.UNIT_UNAVAILABLE,
                        "Early pickup refused: " + unit.getAssetTag() + " is booked from "
                                + other.startDate + " to " + other.endDate + ".");
            }
        }
        startDate = today;
    }

    /**
     * Late-return failsafe: moves a booking that hasn't been picked up yet onto another unit of
     * the same model, when its own unit is still out with someone else. The replacement must be
     * free for every day of this booking. Dates and everything else stay the same.
     *
     * @param replacement         another unit of the same model
     * @param replacementBookings the replacement's active reservations, loaded while it is locked
     * @throws DomainRuleViolation INVALID_RESERVATION_STATE if not RESERVED,
     *                             INVALID_REQUEST if the replacement isn't an identical unit,
     *                             UNIT_UNAVAILABLE if the replacement is booked or out of service
     */
    public void reassignTo(Unit replacement, Collection<Reservation> replacementBookings) {
        requireStatus(ReservationStatus.RESERVED, "moved to another unit");
        if (replacement == unit || (replacement.getId() != null && replacement.getId().equals(unit.getId()))) {
            throw new DomainRuleViolation(DomainRuleViolation.INVALID_REQUEST,
                    "The replacement must be a different unit.");
        }
        if (!sameModel(replacement, unit)) {
            throw new DomainRuleViolation(DomainRuleViolation.INVALID_REQUEST,
                    replacement.getAssetTag() + " is not the same model as " + unit.getAssetTag() + ".");
        }
        replacement.assertAvailable(startDate, endDate, replacementBookings);
        unit = replacement;
    }

    private static boolean sameModel(Unit a, Unit b) {
        EquipmentModel ma = a.getModel();
        EquipmentModel mb = b.getModel();
        if (ma == mb) {
            return true;
        }
        return ma.getId() != null && ma.getId().equals(mb.getId());
    }

    /** Staff take the unit back. Only a PICKED_UP reservation (overdue or not). */
    public void markReturned(Instant now) {
        requireStatus(ReservationStatus.PICKED_UP, "returned");
        status = ReservationStatus.RETURNED;
        returnedAt = now;
    }

    /**
     * A RESERVED reservation stays valid until its endDate; once that day has passed without a
     * pickup it becomes EXPIRED and stops occupying the unit.
     *
     * @return true if this call expired it
     */
    public boolean expireIfUnclaimed(LocalDate today) {
        if (status == ReservationStatus.RESERVED && today.isAfter(endDate)) {
            status = ReservationStatus.EXPIRED;
            return true;
        }
        return false;
    }

    /** Active reservations occupy the unit and count toward the category limit. */
    public boolean isActive() {
        return status.isActive();
    }

    /** Picked up and today is after endDate. A flag, not a status. */
    public boolean isOverdue(LocalDate today) {
        return status == ReservationStatus.PICKED_UP && today.isAfter(endDate);
    }

    /** Do the two inclusive ranges share at least one day? */
    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !startDate.isAfter(otherEnd) && !otherStart.isAfter(endDate);
    }

    private void requireStatus(ReservationStatus required, String action) {
        if (status != required) {
            throw new DomainRuleViolation(DomainRuleViolation.INVALID_RESERVATION_STATE,
                    "A " + status + " reservation cannot be " + action + "; it must be " + required + ".");
        }
    }

    private static DomainRuleViolation invalid(String message) {
        return new DomainRuleViolation(DomainRuleViolation.INVALID_REQUEST, message);
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public User getStudent() { return student; }
    public Unit getUnit() { return unit; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public ReservationStatus getStatus() { return status; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public Instant getReturnedAt() { return returnedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
