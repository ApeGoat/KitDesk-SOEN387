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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false, updatable = false)
    private Unit unit;

    @Column(name = "start_date", nullable = false, updatable = false)
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
     * Staff hand the unit over. Only a RESERVED reservation, and only on a day inside its range:
     * before startDate the unit may still be out with someone else, and after endDate the
     * booking has lapsed.
     */
    public void pickUp(LocalDate today, Instant now) {
        requireStatus(ReservationStatus.RESERVED, "picked up");
        if (today.isBefore(startDate) || today.isAfter(endDate)) {
            throw new DomainRuleViolation(DomainRuleViolation.INVALID_RESERVATION_STATE,
                    "This reservation can only be picked up between " + startDate + " and " + endDate + ".");
        }
        status = ReservationStatus.PICKED_UP;
        pickedUpAt = now;
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
