package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitdesk.support.ApiTestBase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The booking use cases over HTTP: every rule and error code in the contract. */
class ReservationApiTest extends ApiTestBase {

    static final String ALICE = "alice@kitdesk.test";
    static final String BOB = "bob@kitdesk.test";
    static final String CAROL = "carol@kitdesk.test";
    static final String STAFF = "staff@kitdesk.test";

    @Nested
    class Booking {

        @Test
        void aBookingSentAsPlainTextIs415AndBooksNothing() {
            Resp r = send("POST", "/reservations", token(ALICE),
                    bookingBody(unitId("CAM-R5-01"), TODAY.plusDays(1), TODAY.plusDays(3)),
                    "Content-Type", "text/plain", "Idempotency-Key", newKey());

            assertThat(r.status()).isEqualTo(415);
            assertThat(r.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class)).isZero();
        }

        @Test
        void createsAReservationInTheContractShape() {
            Resp r = book(ALICE, "CAM-R5-01", 1, 3);
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.text("id")).matches("res_[0-9a-f]{16}");
            assertThat(r.text("unitId")).isEqualTo(unitId("CAM-R5-01"));
            assertThat(r.text("modelId")).startsWith("mdl_");
            assertThat(r.text("modelName")).isEqualTo("Canon EOS R5");
            assertThat(r.text("category")).isEqualTo("CAMERA");
            assertThat(r.text("startDate")).isEqualTo(TODAY.plusDays(1).toString());
            assertThat(r.text("endDate")).isEqualTo(TODAY.plusDays(3).toString());
            assertThat(r.text("status")).isEqualTo("RESERVED");
            assertThat(r.body().path("overdue").asBoolean()).isFalse();
            assertThat(r.body().path("version").asLong()).isZero();
            assertThat(r.text("createdAt")).isNotBlank();
            assertThat(r.body().has("studentId")).isFalse();
        }

        @Test
        void tenDaysIsFineElevenIs400() {
            assertThat(book(ALICE, "CAM-R5-01", 0, 9).status()).isEqualTo(201);
            Resp eleven = book(BOB, "CAM-R5-02", 0, 10);
            assertThat(eleven.status()).isEqualTo(400);
            assertThat(eleven.code()).isEqualTo("INVALID_REQUEST");
        }

        @Test
        void badDatesAre400() {
            assertThat(book(ALICE, "CAM-R5-01", -1, 2).code()).isEqualTo("INVALID_REQUEST");   // past start
            assertThat(book(ALICE, "CAM-R5-01", 3, 2).code()).isEqualTo("INVALID_REQUEST");    // end before start
            assertThat(send("POST", "/reservations", token(ALICE),
                    "{\"unitId\":\"" + unitId("CAM-R5-01") + "\",\"startDate\":\"tomorrow\",\"endDate\":\"2026-12-01\"}",
                    "Idempotency-Key", newKey()).code()).isEqualTo("INVALID_REQUEST");
        }

        @Test
        void unknownOrMissingFieldsAre400() {
            String body = "{\"unitId\":\"" + unitId("CAM-R5-01") + "\",\"startDate\":\"" + TODAY
                    + "\",\"endDate\":\"" + TODAY + "\",\"studentId\":\"someone-else\"}";
            assertThat(send("POST", "/reservations", token(ALICE), body, "Idempotency-Key", newKey()).status())
                    .isEqualTo(400);
            assertThat(send("POST", "/reservations", token(ALICE), "{\"unitId\":\"x\"}",
                    "Idempotency-Key", newKey()).code()).isEqualTo("INVALID_REQUEST");
            assertThat(send("POST", "/reservations", token(ALICE), null, "Idempotency-Key", newKey()).status())
                    .isEqualTo(400);
        }

        @Test
        void staffCannotBook() {
            Resp r = book(STAFF, "CAM-R5-01", 1, 2);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("FORBIDDEN");
        }

        @Test
        void lapsedSubscriptionIs403() {
            Resp r = book(CAROL, "CAM-R5-01", 1, 2);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("SUBSCRIPTION_INACTIVE");
        }

        @Test
        void overdueGearBlocksNewBookings() {
            insertReservation(ALICE, "SND-H6-01", TODAY.minusDays(5), TODAY.minusDays(1), "PICKED_UP");
            Resp r = book(ALICE, "CAM-R5-01", 1, 2);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("OVERDUE_GEAR");
        }

        @Test
        void unknownUnitIs404() {
            Resp r = send("POST", "/reservations", token(ALICE), bookingBody("unt_nope", TODAY, TODAY),
                    "Idempotency-Key", newKey());
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.code()).isEqualTo("NOT_FOUND");
        }

        @Test
        void overlappingDatesAre409() {
            assertThat(book(ALICE, "CAM-R5-01", 1, 3).status()).isEqualTo(201);
            Resp r = book(BOB, "CAM-R5-01", 3, 5);   // shares day 3
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("UNIT_UNAVAILABLE");
            assertThat(book(BOB, "CAM-R5-01", 4, 6).status()).isEqualTo(201);   // back-to-back is fine
        }

        @Test
        void categoryLimitIs409OnOverlappingDays() {
            assertThat(book(ALICE, "CAM-R5-01", 1, 3).status()).isEqualTo(201);
            Resp second = book(ALICE, "CAM-A7-01", 3, 4);   // cameras: limit 1, shares day 3
            assertThat(second.status()).isEqualTo(409);
            assertThat(second.code()).isEqualTo("CATEGORY_LIMIT_REACHED");
            assertThat(book(ALICE, "CAM-A7-01", 5, 6).status()).isEqualTo(201);   // another week: fine

            assertThat(book(ALICE, "LEN-50-01", 1, 2).status()).isEqualTo(201);   // lenses: limit 2
            assertThat(book(ALICE, "LEN-50-02", 1, 2).status()).isEqualTo(201);
            assertThat(book(ALICE, "LEN-50-03", 1, 2).code()).isEqualTo("CATEGORY_LIMIT_REACHED");
        }

        @Test
        void cancelledBookingsDontCountTowardTheLimit() {
            Resp first = book(ALICE, "CAM-R5-01", 1, 2);
            assertThat(change(ALICE, first.text("id"), "cancel", 0).status()).isEqualTo(200);
            assertThat(book(ALICE, "CAM-A7-01", 1, 2).status()).isEqualTo(201);
        }

        @Test
        void aUnitThatIsOverdueRightNowCantBeBookedFromToday() {
            insertReservation(BOB, "CAM-R5-01", TODAY.minusDays(4), TODAY.minusDays(2), "PICKED_UP");   // not back
            Resp r = book(ALICE, "CAM-R5-01", 0, 1);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("UNIT_UNAVAILABLE");
        }

        @Test
        void outOfServiceUnitIs409() {
            jdbc.update("UPDATE units SET active = FALSE WHERE asset_tag = 'CAM-R5-03'");
            Resp r = book(ALICE, "CAM-R5-03", 1, 2);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("UNIT_UNAVAILABLE");
        }
    }

    @Nested
    class Reading {

        @Test
        void studentsSeeOnlyTheirOwnReservations() {
            String alices = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            String bobs = book(BOB, "CAM-R5-02", 1, 2).text("id");

            assertThat(get("/reservations", token(ALICE)).body().path("reservations")).hasSize(1);
            assertThat(get("/reservations/" + alices, token(ALICE)).status()).isEqualTo(200);

            Resp other = get("/reservations/" + bobs, token(ALICE));
            assertThat(other.status()).isEqualTo(404);   // as if it didn't exist
            assertThat(other.code()).isEqualTo("NOT_FOUND");
        }

        @Test
        void staffSeeEveryone() {
            String bobs = book(BOB, "CAM-R5-02", 1, 2).text("id");
            book(ALICE, "CAM-R5-01", 1, 2);
            assertThat(get("/reservations", token(STAFF)).body().path("reservations")).hasSize(2);
            assertThat(get("/reservations/" + bobs, token(STAFF)).status()).isEqualTo(200);
        }

        @Test
        void filtersByStatusAndOverdue() {
            insertReservation(BOB, "SND-H6-01", TODAY.minusDays(5), TODAY.minusDays(1), "PICKED_UP");
            insertReservation(ALICE, "SND-NTG3-01", TODAY.minusDays(1), TODAY.plusDays(2), "PICKED_UP");
            book(ALICE, "CAM-R5-01", 1, 2);
            String staff = token(STAFF);

            Resp overdue = get("/reservations?overdue=true", staff);
            assertThat(overdue.body().path("reservations")).hasSize(1);
            assertThat(overdue.body().path("reservations").get(0).path("overdue").asBoolean()).isTrue();
            assertThat(get("/reservations?overdue=false", staff).body().path("reservations")).hasSize(2);
            assertThat(get("/reservations?status=PICKED_UP", staff).body().path("reservations")).hasSize(2);
            assertThat(get("/reservations?status=RESERVED&overdue=false", staff).body().path("reservations")).hasSize(1);
        }

        @Test
        void staffOverdueListShowsWhoHasWhatOldestDueFirst() {
            insertReservation(ALICE, "SND-NTG3-01", TODAY.minusDays(5), TODAY.minusDays(1), "PICKED_UP");
            insertReservation(BOB, "CAM-R5-03", TODAY.minusDays(8), TODAY.minusDays(4), "PICKED_UP");
            insertReservation(BOB, "SND-H6-01", TODAY.minusDays(1), TODAY.plusDays(2), "PICKED_UP");   // not late

            JsonNode list = get("/reservations?overdue=true", token(STAFF)).body().path("reservations");

            assertThat(list).hasSize(2);
            assertThat(list.get(0).path("assetTag").asText()).isEqualTo("CAM-R5-03");   // due longest ago
            assertThat(list.get(0).path("endDate").asText()).isEqualTo(TODAY.minusDays(4).toString());
            assertThat(list.get(0).path("student").path("email").asText()).isEqualTo(BOB);
            assertThat(list.get(0).path("student").path("name").asText()).isEqualTo("Bob Student");
            assertThat(list.get(1).path("assetTag").asText()).isEqualTo("SND-NTG3-01");
            assertThat(list.get(1).path("student").path("email").asText()).isEqualTo(ALICE);
        }

        @Test
        void studentsSeeTheAssetTagButNotTheStudentBlock() {
            String alices = book(ALICE, "CAM-R5-01", 1, 2).text("id");

            JsonNode own = get("/reservations", token(ALICE)).body().path("reservations").get(0);
            assertThat(own.path("assetTag").asText()).isEqualTo("CAM-R5-01");
            assertThat(own.has("student")).isFalse();
            assertThat(get("/reservations/" + alices, token(ALICE)).body().has("student")).isFalse();
            assertThat(get("/reservations/" + alices, token(STAFF)).body().path("student").path("email").asText())
                    .isEqualTo(ALICE);
        }

        @Test
        void staffStatusFilterComesFromTheDatabase() {
            insertReservation(BOB, "SND-H6-01", TODAY.minusDays(5), TODAY.minusDays(1), "PICKED_UP");
            String reserved = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            insertReservation(BOB, "CAM-R5-02", TODAY.plusDays(1), TODAY.plusDays(2), "CANCELLED");

            JsonNode list = get("/reservations?status=RESERVED", token(STAFF)).body().path("reservations");
            assertThat(list).hasSize(1);
            assertThat(list.get(0).path("id").asText()).isEqualTo(reserved);
            assertThat(get("/reservations?status=PICKED_UP&overdue=true", token(STAFF)).body().path("reservations"))
                    .hasSize(1);
            assertThat(get("/reservations?status=RESERVED&overdue=true", token(STAFF)).body().path("reservations"))
                    .isEmpty();
        }

        @Test
        void badFilterValuesAre400() {
            assertThat(get("/reservations?status=LOST", token(STAFF)).code()).isEqualTo("INVALID_REQUEST");
            assertThat(get("/reservations?overdue=maybe", token(STAFF)).code()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Nested
    class Cancelling {

        @Test
        void cancelBumpsTheVersion() {
            String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            Resp r = change(ALICE, id, "cancel", 0);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.text("status")).isEqualTo("CANCELLED");
            assertThat(r.body().path("version").asLong()).isEqualTo(1);
        }

        @Test
        void missingVersionIs428() {
            String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            Resp r = send("POST", "/reservations/" + id + "/cancel", token(ALICE), null, "Idempotency-Key", newKey());
            assertThat(r.status()).isEqualTo(428);
            assertThat(r.code()).isEqualTo("VERSION_REQUIRED");
        }

        @Test
        void staleVersionIs409() {
            String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            Resp r = change(ALICE, id, "cancel", 7);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("RESERVATION_VERSION_MISMATCH");
            assertThat(get("/reservations/" + id, token(ALICE)).text("status")).isEqualTo("RESERVED");
        }

        @Test
        void badVersionHeaderIs400() {
            String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            Resp r = send("POST", "/reservations/" + id + "/cancel", token(ALICE), null,
                    "Idempotency-Key", newKey(), "If-Match-Version", "latest");
            assertThat(r.status()).isEqualTo(400);
        }

        @Test
        void cancellingTwiceIs409InvalidState() {
            String id = book(ALICE, "CAM-R5-01", 1, 2).text("id");
            change(ALICE, id, "cancel", 0);
            Resp again = change(ALICE, id, "cancel", 1);
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.code()).isEqualTo("INVALID_RESERVATION_STATE");
        }

        @Test
        void someoneElsesBookingIs404() {
            String id = book(BOB, "CAM-R5-01", 1, 2).text("id");
            assertThat(change(ALICE, id, "cancel", 0).status()).isEqualTo(404);
            assertThat(get("/reservations/" + id, token(BOB)).text("status")).isEqualTo("RESERVED");
        }

        @Test
        void staffCantCancelAndGet403() {
            String id = book(BOB, "CAM-R5-01", 1, 2).text("id");
            Resp r = change(STAFF, id, "cancel", 0);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("FORBIDDEN");
            assertThat(get("/reservations/" + id, token(BOB)).text("status")).isEqualTo("RESERVED");
        }
    }

    @Nested
    class PickupAndReturn {

        @Test
        void studentsCantRecordPickupsOrReturns() {
            String id = book(ALICE, "CAM-R5-01", 0, 2).text("id");
            Resp pickup = change(ALICE, id, "pickup", 0);
            assertThat(pickup.status()).isEqualTo(403);
            assertThat(pickup.code()).isEqualTo("FORBIDDEN");
            assertThat(change(ALICE, id, "return", 0).code()).isEqualTo("FORBIDDEN");
        }

        @Test
        void fullFlowReservedPickedUpReturned() {
            String id = book(ALICE, "CAM-R5-01", 0, 2).text("id");
            Resp picked = change(STAFF, id, "pickup", 0);
            assertThat(picked.status()).isEqualTo(200);
            assertThat(picked.text("status")).isEqualTo("PICKED_UP");
            assertThat(picked.body().path("version").asLong()).isEqualTo(1);

            Resp returned = change(STAFF, id, "return", 1);
            assertThat(returned.status()).isEqualTo(200);
            assertThat(returned.text("status")).isEqualTo("RETURNED");
            assertThat(returned.body().path("version").asLong()).isEqualTo(2);
        }

        @Test
        void returnBeforePickupIs409() {
            String id = book(ALICE, "CAM-R5-01", 0, 2).text("id");
            assertThat(change(STAFF, id, "return", 0).code()).isEqualTo("INVALID_RESERVATION_STATE");
        }

        @Test
        void unknownReservationIs404() {
            assertThat(change(STAFF, "res_nope", "pickup", 0).status()).isEqualTo(404);
        }

        @Test
        void overdueGearBlocksPickup() {
            String id = book(ALICE, "CAM-R5-01", 0, 2).text("id");
            insertReservation(ALICE, "SND-H6-01", TODAY.minusDays(5), TODAY.minusDays(1), "PICKED_UP");
            Resp r = change(STAFF, id, "pickup", 0);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("OVERDUE_GEAR");
        }

        @Test
        void earlyPickupMovesTheStartAndTrimsTheEnd() {
            String id = book(ALICE, "CAM-R5-01", 3, 12).text("id");   // 10 days, starting in 3 days
            Resp r = change(STAFF, id, "pickup", 0);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.text("startDate")).isEqualTo(TODAY.toString());
            assertThat(r.text("endDate")).isEqualTo(TODAY.plusDays(9).toString());
        }

        @Test
        void earlyPickupRefusedIfSomeoneIsBookedInBetween() {
            book(BOB, "CAM-R5-01", 1, 2);
            String id = book(ALICE, "CAM-R5-01", 3, 5).text("id");
            Resp r = change(STAFF, id, "pickup", 0);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("UNIT_UNAVAILABLE");
            Resp after = get("/reservations/" + id, token(ALICE));
            assertThat(after.text("startDate")).isEqualTo(TODAY.plusDays(3).toString());
            assertThat(after.text("status")).isEqualTo("RESERVED");
        }

        @Test
        void onTimePickupRefusedWhileTheUnitIsOverdueWithSomeoneElse() {
            String id = book(ALICE, "CAM-R5-01", 1, 3).text("id");
            // Bob's earlier booking of the same unit ended yesterday and he still has it.
            insertReservation(BOB, "CAM-R5-01", TODAY.minusDays(3), TODAY.minusDays(1), "PICKED_UP");
            // Alice's booking starts today. (Set directly: booking it from today now would be refused,
            // because the unit is overdue. That was made before Bob was late.)
            jdbc.update("UPDATE reservations SET start_date = ? WHERE public_id = ?", TODAY, id);
            Resp r = change(STAFF, id, "pickup", 0);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("UNIT_UNAVAILABLE");
            assertThat(get("/reservations/" + id, token(ALICE)).text("status")).isEqualTo("RESERVED");
        }

        @Test
        void earlyPickupRefusedIfItWouldBreakTheCategoryLimit() {
            book(ALICE, "CAM-A7-01", 0, 1);                       // a camera today and tomorrow
            String id = book(ALICE, "CAM-R5-01", 3, 5).text("id"); // another one from day 3: fine
            Resp r = change(STAFF, id, "pickup", 0);               // picking it up today adds days 0-2
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("CATEGORY_LIMIT_REACHED");
            assertThat(get("/reservations/" + id, token(ALICE)).text("startDate")).isEqualTo(TODAY.plusDays(3).toString());
        }

        @Test
        void earlyPickupRefusedIfTheUnitIsStillOut() {
            insertReservation(BOB, "CAM-R5-01", TODAY.minusDays(3), TODAY.minusDays(1), "PICKED_UP");
            String id = book(ALICE, "CAM-R5-01", 2, 4).text("id");
            assertThat(change(STAFF, id, "pickup", 0).code()).isEqualTo("UNIT_UNAVAILABLE");
        }
    }
}
