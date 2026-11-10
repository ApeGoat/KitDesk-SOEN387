package com.kitdesk.service;

import com.kitdesk.domain.Category;
import com.kitdesk.domain.CategoryCode;
import com.kitdesk.domain.EquipmentModel;
import com.kitdesk.domain.Reservation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Role;
import com.kitdesk.domain.Unit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the services hand back: plain read-only records shaped like the contract's schemas,
 * using public ids only. Entities never leave the service layer, so the interface layer can't
 * change them by accident and lazy loading never happens outside a transaction.
 */
public final class Views {

    private Views() {
    }

    public record LoginResult(String token, String tokenType, long expiresIn, Role role) {
    }

    public record CategoryView(String id, CategoryCode code, String name, int rentalLimit) {
        static CategoryView of(Category c) {
            return new CategoryView(c.getPublicId(), c.getCode(), c.getName(), c.getRentalLimit());
        }
    }

    public record ModelView(String id, String name, CategoryCode category, long unitCount) {
        static ModelView of(EquipmentModel m, long unitCount) {
            return new ModelView(m.getPublicId(), m.getDisplayName(), m.getCategory().getCode(), unitCount);
        }
    }

    /**
     * One booking on a unit's calendar. {@code occupiedUntil} is the last day the unit is busy:
     * the end date, or today for gear that is picked up and not returned yet
     * ({@code notReturned}), since an overdue unit stays busy until it comes back.
     */
    public record UnitBookingView(LocalDate startDate, LocalDate endDate, ReservationStatus status,
                                  LocalDate occupiedUntil, boolean notReturned) {
        static UnitBookingView of(Reservation r, LocalDate today) {
            LocalDate until = r.occupiedUntil(today);
            return new UnitBookingView(r.getStartDate(), r.getEndDate(), r.getStatus(),
                    until, until.isAfter(r.getEndDate()));
        }
    }

    /** {@code inService} is false for a unit taken out of service: it is listed but can't be booked. */
    public record UnitView(String id, String assetTag, String modelId, boolean inService,
                           List<UnitBookingView> reservations) {
        static UnitView of(Unit u, List<Reservation> active, LocalDate today) {
            return new UnitView(u.getPublicId(), u.getAssetTag(), u.getModel().getPublicId(), u.isActive(),
                    active.stream().map(r -> UnitBookingView.of(r, today)).toList());
        }
    }

    /** Who holds a reservation. Only ever shown to staff. */
    public record StudentView(String name, String email) {
    }

    /**
     * A reservation. {@code student} is null (left out of the JSON) unless the service decided
     * the caller may see it, which today means staff.
     */
    public record ReservationView(String id, String unitId, String assetTag, String modelId, String modelName,
                                  CategoryCode category, LocalDate startDate, LocalDate endDate,
                                  ReservationStatus status, boolean overdue, long version,
                                  Instant createdAt, StudentView student) {
        static ReservationView of(Reservation r, LocalDate today, boolean includeStudent) {
            EquipmentModel m = r.getUnit().getModel();
            StudentView student = includeStudent
                    ? new StudentView(r.getStudent().getFullName(), r.getStudent().getEmail())
                    : null;
            return new ReservationView(r.getPublicId(), r.getUnit().getPublicId(), r.getUnit().getAssetTag(),
                    m.getPublicId(), m.getDisplayName(), m.getCategory().getCode(), r.getStartDate(),
                    r.getEndDate(), r.getStatus(), r.isOverdue(today), r.getVersion(), r.getCreatedAt(), student);
        }
    }
}
