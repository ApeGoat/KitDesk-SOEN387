package com.kitdesk.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contract's health checks. Spring Boot does the actual checking (its "liveness" and
 * "readiness" health groups); this only puts the result at the contract's paths and in the
 * contract's {@code HealthStatus} shape.
 */
@RestController
public class HealthController {

    private final HealthEndpoint health;

    public HealthController(HealthEndpoint health) {
        this.health = health;
    }

    /** Is the process running? */
    @GetMapping("/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return respond(health.healthForPath("liveness"), false);
    }

    /** Can it take requests, including reaching the database? 503 if not. */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        return respond(health.healthForPath("readiness"), true);
    }

    private static ResponseEntity<Map<String, String>> respond(HealthComponent result, boolean withDatabase) {
        boolean up = result != null && Status.UP.equals(result.getStatus());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        if (withDatabase) {
            body.put("database", databaseStatus(result));
        }
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static String databaseStatus(HealthComponent result) {
        if (result instanceof CompositeHealth composite && composite.getComponents() != null) {
            HealthComponent db = composite.getComponents().get("db");
            if (db != null && Status.UP.equals(db.getStatus())) {
                return "UP";
            }
        }
        return "DOWN";
    }
}
