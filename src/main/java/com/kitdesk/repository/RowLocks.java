package com.kitdesk.repository;

import com.kitdesk.domain.Unit;
import com.kitdesk.domain.User;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The one place the service layer takes its row locks from. It only forwards to
 * {@link UserRepository#lockStudent} and {@link UnitRepository#lockUnit}.
 *
 * <p>Having a single entry point lets the "without locks" race tests replace this bean with one
 * that does plain reads, to show what the database constraint still guarantees on its own.
 * There is no switch in the application itself: the app always locks.
 *
 * <p>Lock order is the service's decision: student first, then unit. Always the same order,
 * so two bookings can't each hold one lock and wait for the other (deadlock).
 */
@Component
public class RowLocks {

    private final UserRepository users;
    private final UnitRepository units;

    public RowLocks(UserRepository users, UnitRepository units) {
        this.users = users;
        this.units = units;
    }

    public Optional<User> lockStudent(Long userId) {
        return users.lockStudent(userId);
    }

    public Optional<Unit> lockUnit(String unitPublicId) {
        return units.lockUnit(unitPublicId);
    }
}
