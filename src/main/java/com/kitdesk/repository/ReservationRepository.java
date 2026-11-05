package com.kitdesk.repository;

import com.kitdesk.domain.Reservation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Unit;
import com.kitdesk.domain.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** Name of the database rule that refuses overlapping active reservations of one unit (V1). */
    String OVERLAP_CONSTRAINT = "no_overlapping_active_reservations";

    @Query("""
            select r from Reservation r
            join fetch r.unit u join fetch u.model m join fetch m.category
            where r.publicId = :publicId""")
    Optional<Reservation> findByPublicId(@Param("publicId") String publicId);

    /**
     * Optimistic check for cancel / pickup / return: loads the reservation only if its stored
     * version still equals the version the client last read ({@code If-Match-Version}).
     * Hibernate's {@code @Version} then also guards the UPDATE itself, so a change that sneaks in
     * between this read and the commit is caught too (ObjectOptimisticLockingFailureException).
     *
     * @throws VersionMismatchException if the reservation exists but its version has moved on
     */
    default Optional<Reservation> findByPublicIdAtVersion(String publicId, int expectedVersion) {
        Optional<Reservation> found = findByPublicId(publicId);
        found.ifPresent(r -> {
            if (r.getVersion() != expectedVersion) {
                throw new VersionMismatchException(publicId, expectedVersion, r.getVersion());
            }
        });
        return found;
    }

    @Query("""
            select r from Reservation r
            join fetch r.unit u join fetch u.model m join fetch m.category
            where r.student = :student order by r.createdAt desc""")
    List<Reservation> findByStudent(@Param("student") User student);

    @Query("""
            select r from Reservation r
            join fetch r.unit u join fetch u.model m join fetch m.category
            order by r.createdAt desc""")
    List<Reservation> findAllNewestFirst();

    /** The unit's active (RESERVED or PICKED_UP) reservations: what occupies its calendar. */
    @Query("""
            select r from Reservation r
            where r.unit = :unit and r.status in (com.kitdesk.domain.ReservationStatus.RESERVED,
                                                  com.kitdesk.domain.ReservationStatus.PICKED_UP)
            order by r.startDate""")
    List<Reservation> findActiveByUnit(@Param("unit") Unit unit);

    /** How many active reservations the student holds in a category, whatever their dates. */
    @Query("""
            select count(r) from Reservation r
            where r.student.id = :studentId and r.unit.model.category.id = :categoryId
              and r.status in (com.kitdesk.domain.ReservationStatus.RESERVED,
                               com.kitdesk.domain.ReservationStatus.PICKED_UP)""")
    long countActiveInCategory(@Param("studentId") Long studentId, @Param("categoryId") Long categoryId);

    /** Does the student hold gear that's past its end date? (Overdue = PICKED_UP and today > endDate.) */
    @Query("""
            select count(r) > 0 from Reservation r
            where r.student.id = :studentId
              and r.status = com.kitdesk.domain.ReservationStatus.PICKED_UP
              and r.endDate < :today""")
    boolean existsOverdue(@Param("studentId") Long studentId, @Param("today") LocalDate today);

    /** RESERVED bookings whose end date has passed, for the expiry job. */
    @Query("""
            select r from Reservation r
            where r.status = com.kitdesk.domain.ReservationStatus.RESERVED and r.endDate < :today""")
    List<Reservation> findUnclaimedPastEnd(@Param("today") LocalDate today);

    List<Reservation> findByStatus(ReservationStatus status);
}
