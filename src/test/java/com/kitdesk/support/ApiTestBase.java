package com.kitdesk.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base for the HTTP tests: starts the whole app on a random port against a throwaway
 * PostgreSQL (with the V1 schema and V2 seed data), and sends real HTTP requests to it.
 * Every test starts with no reservations and no saved idempotency keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
public abstract class ApiTestBase {

    protected static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Toronto"));
    protected static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    /** A response: status and parsed JSON body (null if empty). */
    public record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        public String code() {
            return body == null ? null : body.path("code").asText(null);
        }

        public String text(String field) {
            return body.path(field).asText();
        }
    }

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM reservations");
        jdbc.update("UPDATE units SET active = TRUE");
    }

    // --- requests -----------------------------------------------------------------------

    protected Resp send(String method, String path, String token, String body, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            boolean isJson = r.headers().firstValue("Content-Type").orElse("").contains("json");
            JsonNode json = !isJson || r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
            return new Resp(r.statusCode(), json, r);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    protected Resp get(String path, String token) {
        return send("GET", path, token, null);
    }

    protected Resp login(String email, String password) {
        return send("POST", "/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /** A token for a seeded account (every seeded password is "password"). Cached per run. */
    protected String token(String email) {
        return TOKENS.computeIfAbsent(port + "/" + email, k -> {
            Resp r = login(email, "password");
            if (r.status() != 200) {
                throw new IllegalStateException("Login failed for " + email + ": " + r.raw().body());
            }
            return r.text("token");
        });
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    // --- shortcuts ----------------------------------------------------------------------

    protected String unitId(String assetTag) {
        return jdbc.queryForObject("SELECT public_id FROM units WHERE asset_tag = ?", String.class, assetTag);
    }

    protected static String bookingBody(String unitId, LocalDate start, LocalDate end) {
        return "{\"unitId\":\"" + unitId + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}";
    }

    protected Resp book(String email, String assetTag, int startOffset, int endOffset) {
        return book(email, assetTag, startOffset, endOffset, newKey());
    }

    protected Resp book(String email, String assetTag, int startOffset, int endOffset, String key) {
        return send("POST", "/reservations", token(email),
                bookingBody(unitId(assetTag), TODAY.plusDays(startOffset), TODAY.plusDays(endOffset)),
                "Idempotency-Key", key);
    }

    /** POST to cancel / pickup / return with a fresh key and the given version. */
    protected Resp change(String email, String reservationId, String action, long version) {
        return send("POST", "/reservations/" + reservationId + "/" + action, token(email), null,
                "Idempotency-Key", newKey(), "If-Match-Version", Long.toString(version));
    }

    /**
     * Puts a reservation straight into the database, e.g. one picked up in the past (overdue).
     * Returns its public id.
     */
    protected String insertReservation(String email, String assetTag, LocalDate start, LocalDate end, String status) {
        jdbc.update("""
                INSERT INTO reservations (student_id, unit_id, start_date, end_date, status, picked_up_at)
                VALUES ((SELECT id FROM users WHERE email = ?), (SELECT id FROM units WHERE asset_tag = ?),
                        ?, ?, ?, CASE WHEN ? = 'PICKED_UP' THEN now() END)""",
                email, assetTag, start, end, status, status);
        return jdbc.queryForObject("SELECT public_id FROM reservations ORDER BY id DESC LIMIT 1", String.class);
    }
}
