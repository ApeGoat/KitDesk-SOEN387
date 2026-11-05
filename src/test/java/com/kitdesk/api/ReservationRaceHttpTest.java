package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The races with the app's normal locks (student row, then unit row). */
class ReservationRaceHttpTest extends RaceScenarios {

    @Test
    void fiftyStudentsOneUnitExactlyOneWins() throws Exception {
        List<Resp> results = fiftyStudentsBookOneUnit();
        assertThat(statusCounts(results)).containsEntry(201, 1L).containsEntry(409, 49L).hasSize(2);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Resp::code)).containsOnly("UNIT_UNAVAILABLE");
        assertThat(activeBookingsOf(CONTESTED_UNIT)).isEqualTo(1);
    }

    @Test
    void oneStudentFiveLensesAtOnceGetsTwo() throws Exception {
        List<Resp> results = oneStudentBooksFiveLenses();
        assertThat(statusCounts(results)).containsEntry(201, 2L).containsEntry(409, 3L).hasSize(2);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Resp::code))
                .containsOnly("CATEGORY_LIMIT_REACHED");
        assertThat(activeLensesOf("alice@kitdesk.test")).isEqualTo(2);
    }
}
