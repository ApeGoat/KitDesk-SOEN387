package com.kitdesk.service;

import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.Reservation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Unit;
import com.kitdesk.repository.IdempotencyRepository;
import com.kitdesk.repository.ReservationRepository;
import com.kitdesk.repository.RowLocks;
import com.kitdesk.repository.UnitRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled use cases that no request triggers:
 * <ul>
 *   <li><b>Expiry</b> (daily): RESERVED bookings whose end date has passed become EXPIRED,
 *       each in its own transaction.</li>
 *   <li><b>Late-return failsafe</b> (hourly): a booking starting today or tomorrow whose unit is
 *       still out past its end date is moved to an identical unit free for the whole booking.
 *       If none is free, it stays as it is; the student will be told and offered a switch or a
 *       wait, once the email outbox exists (contract v2 log, entry 4).</li>
 *   <li><b>Key cleanup</b> (hourly): idempotency keys older than 24 hours are forgotten.</li>
 * </ul>
 * Each run can also be called directly (the tests do).
 */
@Service
public class ReservationJobs {

    /** How far ahead the failsafe looks: bookings starting today or tomorrow. */
    static final int LOOKAHEAD_DAYS = 1;
    static final Duration KEY_RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ReservationJobs.class);

    public record ReassignmentRun(int moved, int stillBlocked) {
    }

    private final ReservationRepository reservations;
    private final UnitRepository units;
    private final RowLocks locks;
    private final IdempotencyRepository keys;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReservationJobs(ReservationRepository reservations, UnitRepository units, RowLocks locks,
                           IdempotencyRepository keys, PlatformTransactionManager transactions, Clock clock) {
        this.reservations = reservations;
        this.units = units;
        this.locks = locks;
        this.keys = keys;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Scheduled(cron = "${kitdesk.jobs.expiry-cron:0 5 0 * * *}", zone = "${kitdesk.timezone:America/Toronto}")
    public int expireUnclaimed() {
        LocalDate today = LocalDate.now(clock);
        List<Long> lapsed = reservations.findUnclaimedPastEnd(today).stream().map(Reservation::getId).toList();
        int expired = 0;
        for (Long id : lapsed) {
            // One transaction per booking: a conflict on one (say, staff touching it at 00:05)
            // is logged and skipped, and the next run picks it up again.
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> expireOne(id, today)))) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire reservation {} this run: {}", publicIdOf(id), e.toString());
            }
        }
        if (expired > 0) {
            log.info("Expired {} unclaimed reservation(s)", expired);
        }
        return expired;
    }

    private boolean expireOne(Long reservationId, LocalDate today) {
        Reservation r = reservations.findById(reservationId).orElse(null);
        if (r == null || !r.expireIfUnclaimed(today)) {
            return false;   // changed meanwhile
        }
        reservations.saveAndFlush(r);
        return true;
    }

    @Scheduled(cron = "${kitdesk.jobs.reassign-cron:0 0 * * * *}", zone = "${kitdesk.timezone:America/Toronto}")
    public ReassignmentRun reassignBlockedBookings() {
        LocalDate today = LocalDate.now(clock);
        List<Long> blocked = reservations.findBlockedByOverdueUnit(today, today.plusDays(LOOKAHEAD_DAYS))
                .stream().map(Reservation::getId).toList();
        int moved = 0;
        for (Long id : blocked) {
            // One transaction per booking. A failure (say, the student cancels while it's being
            // moved) rolls back that booking only; it counts as still blocked and the loop goes on.
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> moveToIdenticalUnit(id, today)))) {
                    moved++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not move reservation {} this run: {}", publicIdOf(id), e.toString());
            }
        }
        return new ReassignmentRun(moved, blocked.size() - moved);
    }

    /**
     * Tries the same model's other in-service units in asset-tag order, locking each before
     * checking its calendar (always the same order, so two runs can't deadlock).
     */
    private boolean moveToIdenticalUnit(Long reservationId, LocalDate today) {
        Reservation r = reservations.findById(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.RESERVED) {
            return false;   // cancelled or picked up meanwhile
        }
        Unit original = r.getUnit();
        for (Unit candidate : units.findReplacementCandidates(original.getModel(), original.getId())) {
            Unit locked = locks.lockUnit(candidate.getPublicId()).orElseThrow();
            try {
                r.reassignTo(locked, today, reservations.findActiveByUnit(locked));
            } catch (DomainRuleViolation busy) {
                continue;
            }
            reservations.saveAndFlush(r);
            log.info("Moved reservation {} from {} to {}: {} is overdue",
                    r.getPublicId(), original.getAssetTag(), locked.getAssetTag(), original.getAssetTag());
            // TODO(outbox): tell the student their unit changed (UNIT_REASSIGNED).
            return true;
        }
        log.warn("Reservation {} is blocked: {} is overdue and no identical unit is free",
                r.getPublicId(), original.getAssetTag());
        // TODO(outbox): tell the student, offering a switch to another unit or a wait (UNIT_DELAYED).
        return false;
    }

    private String publicIdOf(Long id) {
        try {
            return reservations.findById(id).map(Reservation::getPublicId).orElse("#" + id);
        } catch (RuntimeException e) {
            return "#" + id;
        }
    }

    @Scheduled(cron = "${kitdesk.jobs.key-cleanup-cron:0 30 * * * *}", zone = "${kitdesk.timezone:America/Toronto}")
    public int forgetOldIdempotencyKeys() {
        Integer deleted = tx.execute(status -> keys.deleteOlderThan(clock.instant().minus(KEY_RETENTION)));
        return deleted == null ? 0 : deleted;
    }
}
