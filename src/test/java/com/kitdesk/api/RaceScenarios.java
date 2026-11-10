package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.support.ApiTestBase;
import com.kitdesk.support.Concurrently;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * The two races from the proposal's race script, fired as real HTTP requests at the running
 * app, all released at the same instant. Run with locks ({@link ReservationRaceHttpTest}) and
 * without ({@link ReservationRaceWithoutLocksHttpTest}).
 */
abstract class RaceScenarios extends ApiTestBase {

    static final String CONTESTED_UNIT = "CAM-R5-03";
    static final String[] LENSES = {"LEN-2470-01", "LEN-2470-02", "LEN-50-01", "LEN-50-02", "LEN-50-03"};

    /** 50 students book the same unit for the same days at the same moment. */
    List<Resp> fiftyStudentsBookOneUnit() throws Exception {
        String body = bookingBody(unitId(CONTESTED_UNIT), TODAY.plusDays(2), TODAY.plusDays(4));
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String token = token(String.format("student%02d@kitdesk.test", i));   // log in first, outside the race
            tasks.add(() -> send("POST", "/reservations", token, body, "Idempotency-Key", newKey()));
        }
        return unwrap(Concurrently.run(tasks));
    }

    /** Alice sends 5 lens bookings (5 different lenses) at once. The lens limit is 2. */
    List<Resp> oneStudentBooksFiveLenses() throws Exception {
        String token = token("alice@kitdesk.test");
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (String lens : LENSES) {
            String body = bookingBody(unitId(lens), TODAY.plusDays(1), TODAY.plusDays(2));
            tasks.add(() -> send("POST", "/reservations", token, body, "Idempotency-Key", newKey()));
        }
        return unwrap(Concurrently.run(tasks));
    }

    static List<Resp> unwrap(List<Concurrently.Outcome<Resp>> outcomes) {
        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        return outcomes.stream().map(Concurrently.Outcome::value).toList();
    }

    static Map<Integer, Long> statusCounts(List<Resp> results) {
        return results.stream().collect(Collectors.groupingBy(Resp::status, Collectors.counting()));
    }

    int activeBookingsOf(String assetTag) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN units u ON u.id = r.unit_id
                WHERE u.asset_tag = ? AND r.status IN ('RESERVED', 'PICKED_UP')""", Integer.class, assetTag);
    }

    int activeLensesOf(String email) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM reservations r
                JOIN units u ON u.id = r.unit_id JOIN models m ON m.id = u.model_id
                JOIN categories c ON c.id = m.category_id JOIN users s ON s.id = r.student_id
                WHERE s.email = ? AND c.code = 'LENS' AND r.status IN ('RESERVED', 'PICKED_UP')""",
                Integer.class, email);
    }
}
