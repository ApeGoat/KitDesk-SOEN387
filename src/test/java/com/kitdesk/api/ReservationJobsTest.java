package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.service.ReservationJobs;
import com.kitdesk.service.ReservationJobs.ReassignmentRun;
import com.kitdesk.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The scheduled jobs, called directly, with results checked over HTTP. */
class ReservationJobsTest extends ApiTestBase {

    static final String ALICE = "alice@kitdesk.test";
    static final String BOB = "bob@kitdesk.test";

    @Autowired
    ReservationJobs jobs;

    /**
     * Makes the database refuse any update to one reservation, the way a conflicting change
     * would make its transaction fail. Removed after each test.
     */
    void failUpdatesOf(String reservationPublicId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION test_refuse_update() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'refused for the test'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_refuse_update BEFORE UPDATE ON reservations FOR EACH ROW "
                + "WHEN (OLD.public_id = '" + reservationPublicId + "') EXECUTE FUNCTION test_refuse_update()");
    }

    @AfterEach
    void removeFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_refuse_update ON reservations");
    }

    @Test
    void oneBookingThatCantBeMovedDoesntStopTheOthers() {
        // Both units are overdue. The camera booking comes first (it starts today) and fails.
        insertReservation(BOB, "CAM-R5-01", TODAY.minusDays(4), TODAY.minusDays(1), "PICKED_UP");
        insertReservation(BOB, "LEN-2470-01", TODAY.minusDays(4), TODAY.minusDays(1), "PICKED_UP");
        String failing = insertReservation(ALICE, "CAM-R5-01", TODAY, TODAY.plusDays(2), "RESERVED");
        String lens = insertReservation(ALICE, "LEN-2470-01", TODAY.plusDays(1), TODAY.plusDays(2), "RESERVED");
        failUpdatesOf(failing);

        ReassignmentRun run = jobs.reassignBlockedBookings();

        assertThat(run).isEqualTo(new ReassignmentRun(1, 1));
        assertThat(get("/reservations/" + lens, token(ALICE)).text("unitId")).isEqualTo(unitId("LEN-2470-02"));
        assertThat(get("/reservations/" + failing, token(ALICE)).text("unitId")).isEqualTo(unitId("CAM-R5-01"));
    }

    @Test
    void oneBookingThatCantBeExpiredDoesntStopTheOthers() {
        String failing = insertReservation(ALICE, "CAM-R5-01", TODAY.minusDays(5), TODAY.minusDays(3), "RESERVED");
        String other = insertReservation(BOB, "CAM-R5-02", TODAY.minusDays(3), TODAY.minusDays(1), "RESERVED");
        failUpdatesOf(failing);

        assertThat(jobs.expireUnclaimed()).isEqualTo(1);
        assertThat(get("/reservations/" + other, token(BOB)).text("status")).isEqualTo("EXPIRED");
        assertThat(get("/reservations/" + failing, token(ALICE)).text("status")).isEqualTo("RESERVED");

        removeFailure();
        assertThat(jobs.expireUnclaimed()).isEqualTo(1);   // the next run picks it up
        assertThat(get("/reservations/" + failing, token(ALICE)).text("status")).isEqualTo("EXPIRED");
    }

    @Test
    void aLapsedBookingCantBeCancelledOverHttp() {
        String lapsed = insertReservation(ALICE, "CAM-R5-01", TODAY.minusDays(3), TODAY.minusDays(1), "RESERVED");
        Resp r = change(ALICE, lapsed, "cancel", 0);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVALID_RESERVATION_STATE");
    }

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
