package com.kitdesk.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.ReservationStatus;
import com.kitdesk.domain.Role;
import com.kitdesk.service.Caller;
import com.kitdesk.service.ErrorCatalog;
import com.kitdesk.service.IdempotencyService;
import com.kitdesk.service.IdempotencyService.Reply;
import com.kitdesk.service.ReservationService;
import com.kitdesk.service.ReservationService.NewBooking;
import com.kitdesk.service.Views.ReservationView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reservation endpoints. Each method reads the request, calls one service method and returns the
 * result. No business rules here.
 *
 * <p>The four writes take the body as raw text and parse it <em>inside</em> the idempotency
 * wrapper. That way a replay is found before the body or the If-Match-Version header is looked
 * at, which is the order the contract promises, and a 400 for a bad body is saved like any other
 * 4xx.
 */
@RestController
public class ReservationController {

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String VERSION_HEADER = "If-Match-Version";

    /** The contract's NewReservation body. Unknown fields are refused by the JSON settings. */
    record NewReservationBody(String unitId, LocalDate startDate, LocalDate endDate) {
    }

    private final ReservationService reservations;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ReservationController(ReservationService reservations, IdempotencyService idempotency, ObjectMapper json) {
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/reservations")
    public Map<String, Object> list(Caller caller,
                                    @RequestParam(required = false) ReservationStatus status,
                                    @RequestParam(required = false) Boolean overdue) {
        return Map.of("reservations", reservations.list(caller, status, overdue));
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationView get(Caller caller, @PathVariable String reservationId) {
        return reservations.get(caller, reservationId);
    }

    @PostMapping("/reservations")
    public ResponseEntity<String> create(Caller caller, HttpServletRequest request,
                                         @RequestHeader(name = KEY_HEADER, required = false) String key,
                                         @RequestBody(required = false) String body) {
        return idempotent(caller, Role.STUDENT, request, key, null, body, 201,
                () -> reservations.create(caller, readNewBooking(body)));
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<String> cancel(Caller caller, HttpServletRequest request, @PathVariable String reservationId,
                                         @RequestHeader(name = KEY_HEADER, required = false) String key,
                                         @RequestHeader(name = VERSION_HEADER, required = false) String version,
                                         @RequestBody(required = false) String body) {
        return idempotent(caller, null, request, key, version, body, 200,
                () -> reservations.cancel(caller, reservationId, readVersion(version)));
    }

    @PostMapping("/reservations/{reservationId}/pickup")
    public ResponseEntity<String> pickUp(Caller caller, HttpServletRequest request, @PathVariable String reservationId,
                                         @RequestHeader(name = KEY_HEADER, required = false) String key,
                                         @RequestHeader(name = VERSION_HEADER, required = false) String version,
                                         @RequestBody(required = false) String body) {
        return idempotent(caller, Role.STAFF, request, key, version, body, 200,
                () -> reservations.pickUp(caller, reservationId, readVersion(version)));
    }

    @PostMapping("/reservations/{reservationId}/return")
    public ResponseEntity<String> markReturned(Caller caller, HttpServletRequest request,
                                               @PathVariable String reservationId,
                                               @RequestHeader(name = KEY_HEADER, required = false) String key,
                                               @RequestHeader(name = VERSION_HEADER, required = false) String version,
                                               @RequestBody(required = false) String body) {
        return idempotent(caller, Role.STAFF, request, key, version, body, 200,
                () -> reservations.markReturned(caller, reservationId, readVersion(version)));
    }

    // ---------------------------------------------------------------------------------------

    private ResponseEntity<String> idempotent(Caller caller, Role role, HttpServletRequest request, String key,
                                              String version, String body, int successStatus,
                                              Supplier<Object> operation) {
        String fingerprint = RequestFingerprint.of(request.getMethod(), request.getRequestURI(), version, body);
        Reply reply = idempotency.run(caller, role, key, fingerprint, successStatus, operation);
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    private NewBooking readNewBooking(String body) {
        if (body == null || body.isBlank()) {
            throw invalid("A request body with unitId, startDate and endDate is required.");
        }
        NewReservationBody parsed;
        try {
            parsed = json.readValue(body, NewReservationBody.class);
        } catch (JsonProcessingException e) {
            throw invalid("Request body could not be read, or contains an unknown or invalid field.");
        }
        if (parsed == null || parsed.unitId() == null || parsed.startDate() == null || parsed.endDate() == null) {
            throw invalid("unitId, startDate and endDate are all required.");
        }
        return new NewBooking(parsed.unitId(), parsed.startDate(), parsed.endDate());
    }

    /** Missing header: null (the service answers 428). Present but not a whole number ≥ 0: 400. */
    private static Integer readVersion(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(header.trim());
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw invalid("If-Match-Version must be a whole number of 0 or more.");
        }
    }

    private static DomainRuleViolation invalid(String message) {
        return new DomainRuleViolation(ErrorCatalog.INVALID_REQUEST, message);
    }
}
