package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.service.ReservationJobs;
import com.kitdesk.service.ReservationJobs.ReassignmentRun;
import com.kitdesk.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The scheduled jobs, called directly, with results checked over HTTP. */
class ReservationJobsTest extends ApiTestBase {

    static final String ALICE = "alice@kitdesk.test";
    static final String BOB = "bob@kitdesk.test";

    @Autowired
    ReservationJobs jobs;

    @Test
    void unclaimedBookingsExpireAfterTheirEndDate() {
        String lapsed = insertReservation(ALICE, "CAM-R5-01", TODAY.minusDays(3), TODAY.minusDays(1), "RESERVED");
        String lastDay = insertReservation(BOB, "CAM-R5-02", TODAY.minusDays(3), TODAY, "RESERVED");

        assertThat(jobs.expireUnclaimed()).isEqualTo(1);
        assertThat(get("/reservations/" + lapsed, token(ALICE)).text("status")).isEqualTo("EXPIRED");
        assertThat(get("/reservations/" + lastDay, token(BOB)).text("status")).isEqualTo("RESERVED");
    }

    @Test
    void bookingBlockedByAnOverdueUnitMovesToAnIdenticalFreeUnit() {
        insertReservation(BOB, "CAM-R5-01", TODAY.minusDays(4), TODAY.minusDays(1), "PICKED_UP");   // not back
        String alices = book(ALICE, "CAM-R5-01", 1, 3).text("id");
        book("student01@kitdesk.test", "CAM-R5-02", 2, 2);   // R5-02 is busy on day 2, so not a fit

        ReassignmentRun run = jobs.reassignBlockedBookings();

        assertThat(run.moved()).isEqualTo(1);
        Resp after = get("/reservations/" + alices, token(ALICE));
        assertThat(after.text("unitId")).isEqualTo(unitId("CAM-R5-03"));
        assertThat(after.text("startDate")).isEqualTo(TODAY.plusDays(1).toString());   // same dates
        assertThat(after.text("endDate")).isEqualTo(TODAY.plusDays(3).toString());
        assertThat(after.body().path("version").asLong()).isEqualTo(1);
    }

    @Test
    void noIdenticalUnitFreeLeavesTheBookingAsItIs() {
        insertReservation(BOB, "CAM-A7-01", TODAY.minusDays(4), TODAY.minusDays(1), "PICKED_UP");   // the only A7
        String alices = book(ALICE, "CAM-A7-01", 1, 3).text("id");

        ReassignmentRun run = jobs.reassignBlockedBookings();

        assertThat(run.moved()).isZero();
        assertThat(run.stillBlocked()).isEqualTo(1);
        Resp after = get("/reservations/" + alices, token(ALICE));
        assertThat(after.text("unitId")).isEqualTo(unitId("CAM-A7-01"));
        assertThat(after.text("status")).isEqualTo("RESERVED");
    }

    @Test
    void bookingsFurtherAheadAreLeftAloneForNow() {
        insertReservation(BOB, "CAM-R5-01", TODAY.minusDays(4), TODAY.minusDays(1), "PICKED_UP");
        book(ALICE, "CAM-R5-01", 5, 6);
        assertThat(jobs.reassignBlockedBookings()).isEqualTo(new ReassignmentRun(0, 0));
    }
}
