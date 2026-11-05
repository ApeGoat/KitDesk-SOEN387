package com.kitdesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitdesk.config.CorrelationIdFilter;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.IdempotencyRecord;
import com.kitdesk.domain.Role;
import com.kitdesk.repository.IdempotencyRepository;
import com.kitdesk.repository.VersionMismatchException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotency keys for the four writes (create, cancel, pickup, return), following the
 * contract's "Idempotency" section.
 *
 * <p>Order of checks: role, then key present (422) and a UUID (400), then look the key up. A
 * saved result is replayed before anything else is looked at, so a retried cancel whose first
 * attempt worked gets its saved 200, not a 409.
 *
 * <p>The operation and the saved key commit in <b>one transaction</b>: either both are stored or
 * neither is. If two requests with the same key arrive at once, the second one's key insert
 * clashes with the first's, its whole transaction (booking included) rolls back, and it
 * replays the first one's result. So one key never books twice.
 *
 * <p>4xx results are saved too (in their own transaction, since the operation rolled back), so
 * a request that first got 409 gets the same 409 again. 5xx results are not saved.
 */
@Service
public class IdempotencyService {

    /** An HTTP result as saved and replayed: status code and JSON body. */
    public record Reply(int status, String body) {
    }

    private static final String KEY_CONSTRAINT = "idempotency_keys_pkey";

    private final IdempotencyRepository keys;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository keys, PlatformTransactionManager transactions,
                              ObjectMapper json, Clock clock) {
        this.keys = keys;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.clock = clock;
    }

    /**
     * Runs {@code operation} at most once per (user, key).
     *
     * @param requiredRole  checked before the key, as the contract orders it; null if any role may call
     * @param fingerprint   hash of method, path, If-Match-Version and body: what "the same request" means
     * @param successStatus the status to answer with when the operation succeeds (200 or 201)
     * @param operation     the use case; its return value is the response body
     */
    public Reply run(Caller caller, Role requiredRole, String key, String fingerprint, int successStatus,
                     Supplier<Object> operation) {
        if (requiredRole != null) {
            Access.require(caller, requiredRole);
        }
        String checkedKey = checkKey(key);
        Optional<Reply> saved = replay(caller, checkedKey, fingerprint);
        if (saved.isPresent()) {
            return saved.get();
        }
        try {
            return tx.execute(status -> {
                Reply reply = new Reply(successStatus, toJson(operation.get()));
                keys.saveAndFlush(record(caller, checkedKey, fingerprint, reply));
                return reply;
            });
        } catch (RuntimeException e) {
            if (isKeyClash(e)) {
                return replayOrFail(caller, checkedKey, fingerprint, e);
            }
            DomainRuleViolation violation = asClientError(e).orElseThrow(() -> e);   // 5xx: not saved
            return saveError(caller, checkedKey, fingerprint, violation);
        }
    }

    private Reply saveError(Caller caller, String key, String fingerprint, DomainRuleViolation violation) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", violation.getCode());
        body.put("message", violation.getMessage());
        body.put("correlationId", CorrelationIdFilter.current());
        Reply reply = new Reply(ErrorCatalog.statusOf(violation.getCode()), toJson(body));
        try {
            tx.executeWithoutResult(status -> keys.saveAndFlush(record(caller, key, fingerprint, reply)));
            return reply;
        } catch (RuntimeException e) {
            if (isKeyClash(e)) {
                return replayOrFail(caller, key, fingerprint, e);
            }
            throw e;
        }
    }

    private Optional<Reply> replay(Caller caller, String key, String fingerprint) {
        return keys.findByUserIdAndKey(caller.userId(), key).map(saved -> {
            if (!saved.matches(fingerprint)) {
                throw new DomainRuleViolation(ErrorCatalog.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request. Use a new key.");
            }
            return new Reply(saved.getResponseStatus(), saved.getResponseBody());
        });
    }

    private Reply replayOrFail(Caller caller, String key, String fingerprint, RuntimeException cause) {
        return replay(caller, key, fingerprint).orElseThrow(() -> cause);
    }

    private static String checkKey(String key) {
        if (key == null || key.isBlank()) {
            throw new DomainRuleViolation(ErrorCatalog.IDEMPOTENCY_KEY_REQUIRED,
                    "The Idempotency-Key header is required for this operation.");
        }
        try {
            return UUID.fromString(key.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new DomainRuleViolation(ErrorCatalog.INVALID_REQUEST, "The Idempotency-Key must be a UUID.");
        }
    }

    /** Errors that mean "fix the request" (4xx), and so are saved and replayed. */
    private static Optional<DomainRuleViolation> asClientError(RuntimeException e) {
        if (e instanceof DomainRuleViolation v && ErrorCatalog.statusOf(v.getCode()) < 500) {
            return Optional.of(v);
        }
        if (e instanceof VersionMismatchException || e instanceof ObjectOptimisticLockingFailureException) {
            return Optional.of(new DomainRuleViolation(ErrorCatalog.RESERVATION_VERSION_MISMATCH,
                    e instanceof VersionMismatchException ? e.getMessage()
                            : "The reservation was changed by someone else. Re-read it and try again."));
        }
        return Optional.empty();
    }

    private static boolean isKeyClash(Throwable e) {
        if (!(e instanceof DataIntegrityViolationException)) {
            return false;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(KEY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private IdempotencyRecord record(Caller caller, String key, String fingerprint, Reply reply) {
        return new IdempotencyRecord(caller.userId(), key, fingerprint, reply.status(), reply.body(), clock.instant());
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the response", e);
        }
    }
}
