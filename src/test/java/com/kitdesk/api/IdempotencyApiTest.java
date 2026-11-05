package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.service.ReservationJobs;
import com.kitdesk.support.ApiTestBase;
import com.kitdesk.support.Concurrently;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The contract's "Idempotency" section, rule by rule. */
class IdempotencyApiTest extends ApiTestBase {

    static final String ALICE = "alice@kitdesk.test";
    static final String BOB = "bob@kitdesk.test";
    static final String STAFF = "staff@kitdesk.test";

    @Autowired
    ReservationJobs jobs;

    int reservationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class);
    }

    @Test
    void missingKeyIs422() {
        Resp r = send("POST", "/reservations", token(ALICE), bookingBody(unitId("CAM-R5-01"), TODAY, TODAY));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(reservationCount()).isZero();
    }

    @Test
    void keyThatIsNotAUuidIs400() {
        Resp r = send("POST", "/reservations", token(ALICE), bookingBody(unitId("CAM-R5-01"), TODAY, TODAY),
                "Idempotency-Key", "my-key");
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void sameKeySameRequestReplaysTheSavedResultAndBooksOnce() {
        String key = newKey();
        Resp first = book(ALICE, "CAM-R5-01", 1, 2, key);
        Resp again = book(ALICE, "CAM-R5-01", 1, 2, key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.raw().body()).isEqualTo(first.raw().body());
        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void fieldOrderAndWhitespaceDontMakeItADifferentRequest() {
        String key = newKey();
        String unit = unitId("CAM-R5-01");
        Resp first = send("POST", "/reservations", token(ALICE),
                "{\"unitId\":\"" + unit + "\",\"startDate\":\"" + TODAY + "\",\"endDate\":\"" + TODAY + "\"}",
                "Idempotency-Key", key);
        Resp reordered = send("POST", "/reservations", token(ALICE),
                "{ \"endDate\": \"" + TODAY + "\",\n \"unitId\": \"" + unit + "\", \"startDate\": \"" + TODAY + "\" }",
                "Idempotency-Key", key);
        assertThat(reordered.status()).isEqualTo(201);
        assertThat(reordered.text("id")).isEqualTo(first.text("id"));
    }

    @Test
    void sameKeyDifferentRequestIs422() {
        String key = newKey();
        book(ALICE, "CAM-R5-01", 1, 2, key);
        Resp other = book(ALICE, "CAM-R5-01", 1, 3, key);
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void keysAreScopedToTheUser() {
        String key = newKey();
        assertThat(book(ALICE, "CAM-R5-01", 1, 2, key).status()).isEqualTo(201);
        assertThat(book(BOB, "CAM-R5-02", 1, 2, key).status()).isEqualTo(201);   // Bob's own key space
        assertThat(reservationCount()).isEqualTo(2);
    }

    @Test
    void savedErrorsReplayToo() {
        String bobs = book(BOB, "CAM-R5-01", 1, 2).text("id");
        String key = newKey();
        Resp first = book(ALICE, "CAM-R5-01", 1, 2, key);
        assertThat(first.code()).isEqualTo("UNIT_UNAVAILABLE");

        change(BOB, bobs, "cancel", 0);   // the unit is free now...
        Resp again = book(ALICE, "CAM-R5-01", 1, 2, key);
        assertThat(again.status()).isEqualTo(409);   // ...but the same request gets the same answer
        assertThat(again.raw().body()).isEqualTo(first.raw().body());
    }

    @Test
    void retriedCancelGetsTheSaved200NotAVersionConflict() {
        String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
        String key = newKey();
        Resp first = send("POST", "/reservations/" + id + "/cancel", token(ALICE), null,
                "Idempotency-Key", key, "If-Match-Version", "0");
        Resp retry = send("POST", "/reservations/" + id + "/cancel", token(ALICE), null,
                "Idempotency-Key", key, "If-Match-Version", "0");
        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.raw().body()).isEqualTo(first.raw().body());
    }

    @Test
    void differentVersionHeaderIsADifferentRequest() {
        String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
        String key = newKey();
        send("POST", "/reservations/" + id + "/cancel", token(ALICE), null, "Idempotency-Key", key, "If-Match-Version", "5");
        Resp other = send("POST", "/reservations/" + id + "/cancel", token(ALICE), null,
                "Idempotency-Key", key, "If-Match-Version", "0");
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void roleIsCheckedBeforeTheKey() {
        // A student calling a staff-only operation without a key gets 403, not 422.
        String id = book(ALICE, "CAM-R5-01", 0, 1).text("id");
        Resp r = send("POST", "/reservations/" + id + "/pickup", token(ALICE), null, "If-Match-Version", "0");
        assertThat(r.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void sameKeySentTwiceAtOnceStillBooksOnce() throws Exception {
        String key = newKey();
        String token = token(ALICE);
        String body = bookingBody(unitId("CAM-R5-01"), TODAY.plusDays(1), TODAY.plusDays(2));
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> send("POST", "/reservations", token, body, "Idempotency-Key", key));
        }
        List<Concurrently.Outcome<Resp>> outcomes = Concurrently.run(tasks);
        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.succeeded()).isTrue();
            assertThat(o.value().status()).isEqualTo(201);
        });
        assertThat(outcomes.stream().map(o -> o.value().text("id")).distinct()).hasSize(1);
        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void keysOlderThan24HoursAreForgotten() {
        String key = newKey();
        book(ALICE, "CAM-R5-01", 1, 2, key);
        jdbc.update("UPDATE idempotency_keys SET created_at = now() - interval '25 hours'");
        assertThat(jobs.forgetOldIdempotencyKeys()).isEqualTo(1);
        // Treated as a new request: Alice already holds a camera (from the first call), so it's refused.
        assertThat(book(ALICE, "CAM-R5-01", 1, 2, key).code()).isEqualTo("CATEGORY_LIMIT_REACHED");
    }
}
