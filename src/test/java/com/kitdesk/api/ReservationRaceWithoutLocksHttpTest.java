package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.domain.Unit;
import com.kitdesk.domain.User;
import com.kitdesk.repository.RowLocks;
import com.kitdesk.repository.UnitRepository;
import com.kitdesk.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The same races with the row locks taken out: inside this test only, the service's
 * {@link RowLocks} is replaced by one that does plain reads. Nothing in the app changes.
 *
 * <p>What it shows: the database's exclusion constraint still stops the unit being double-booked
 * on its own, but nothing in the database backs up the category limit, so without the student
 * lock one student gets more lenses than allowed.
 */
@Import(ReservationRaceWithoutLocksHttpTest.NoLocks.class)
class ReservationRaceWithoutLocksHttpTest extends RaceScenarios {

    @TestConfiguration(proxyBeanMethods = false)
    static class NoLocks {
        @Bean
        @Primary
        RowLocks plainReads(UserRepository users, UnitRepository units) {
            return new RowLocks(users, units) {
                @Override
                public Optional<User> lockStudent(Long userId) {
                    Optional<User> user = users.findById(userId);
                    pause();   // widen the gap between "count" and "insert", like a slow request would
                    return user;
                }

                @Override
                public Optional<Unit> lockUnit(String unitPublicId) {
                    return units.findByPublicId(unitPublicId);
                }
            };
        }

        static void pause() {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void fiftyStudentsOneUnitStillExactlyOneWinsThanksToTheDatabase() throws Exception {
        List<Resp> results = fiftyStudentsBookOneUnit();
        assertThat(statusCounts(results)).containsEntry(201, 1L).containsEntry(409, 49L).hasSize(2);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Resp::code)).containsOnly("UNIT_UNAVAILABLE");
        assertThat(activeBookingsOf(CONTESTED_UNIT)).isEqualTo(1);
    }

    @Test
    void withoutTheStudentLockTheLensLimitBreaks() throws Exception {
        oneStudentBooksFiveLenses();
        assertThat(activeLensesOf("alice@kitdesk.test")).isGreaterThan(2);
    }
}
