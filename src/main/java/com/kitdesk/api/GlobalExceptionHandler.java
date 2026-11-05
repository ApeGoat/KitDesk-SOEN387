package com.kitdesk.api;

import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.repository.VersionMismatchException;
import com.kitdesk.service.ErrorCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into the {@link ApiError} shape, using the codes and statuses from the
 * contract's error catalogue ({@link ErrorCatalog}). Rule violations from the domain and
 * service layers carry their code; this only picks the HTTP status for it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainRuleViolation.class)
    public ResponseEntity<ApiError> ruleViolation(DomainRuleViolation e) {
        return ResponseEntity.status(ErrorCatalog.statusOf(e.getCode())).body(ApiError.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler({VersionMismatchException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> versionMismatch(RuntimeException e) {
        String message = e instanceof VersionMismatchException
                ? e.getMessage()
                : "The reservation was changed by someone else. Re-read it and try again.";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ErrorCatalog.RESERVATION_VERSION_MISMATCH, message));
    }

    /** The database is down or unreachable: 503, retryable with the same key. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ApiError> databaseUnavailable(RuntimeException e) {
        log.error("Database unavailable", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ErrorCatalog.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Try again shortly."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("Request body is invalid.");
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        // Also covers unknown fields, once FAIL_ON_UNKNOWN_PROPERTIES is on (request bodies are closed).
        return badRequest("Request body could not be read, or contains an unknown or invalid field.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> badParameter(MethodArgumentTypeMismatchException e) {
        return badRequest("Parameter '" + e.getName() + "' has an invalid value.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        // Spring's own request errors (unknown URL, wrong method, missing header, ...) carry their
        // proper status. Without this they'd fall through to the 500 below.
        if (e instanceof ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            String code = switch (status.value()) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "NOT_FOUND";
                default -> {
                    HttpStatus known = HttpStatus.resolve(status.value());
                    yield known != null ? known.name() : "REQUEST_FAILED";
                }
            };
            String message = springError.getBody().getDetail() != null
                    ? springError.getBody().getDetail()
                    : "Request could not be handled.";
            return ResponseEntity.status(status).body(ApiError.of(code, message));
        }
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Something went wrong on our side."));
    }

    private static ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", message));
    }
}
