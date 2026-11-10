package com.kitdesk.api;

import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.repository.VersionMismatchException;
import com.kitdesk.service.ErrorCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    /**
     * Spring's own request errors (unknown URL, wrong method, missing header, ...) and anything
     * unexpected. Every response uses a code from the catalogue and a fixed message of ours:
     * Spring's detail text can echo the request (the path, a header), so it isn't passed on.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof ErrorResponse springError) {
            int status = springError.getStatusCode().value();
            ResponseEntity.BodyBuilder reply;
            ApiError body;
            switch (status) {
                case 404 -> {
                    reply = ResponseEntity.status(404);
                    body = ApiError.of(ErrorCatalog.NOT_FOUND, "There is nothing at this address.");
                }
                case 405 -> {
                    reply = ResponseEntity.status(405).headers(springError.getHeaders());   // keeps Allow
                    body = ApiError.of(ErrorCatalog.METHOD_NOT_ALLOWED, "This address doesn't support that method.");
                }
                case 406 -> {
                    // The client said it won't accept JSON, so no error body can be sent (it would
                    // be JSON too): status only, and no catalogue code.
                    return ResponseEntity.status(406).build();
                }
                case 415 -> {
                    reply = ResponseEntity.status(415).headers(springError.getHeaders());   // keeps Accept
                    body = ApiError.of(ErrorCatalog.UNSUPPORTED_MEDIA_TYPE, "Send the request body as application/json.");
                }
                default -> {
                    if (status >= 500) {
                        log.error("Request failed inside Spring", e);
                        reply = ResponseEntity.status(500);
                        body = ApiError.of(ErrorCatalog.INTERNAL_ERROR, "Something went wrong on our side.");
                    } else {
                        // Any other request error (a missing header or parameter, ...) is a 400.
                        reply = ResponseEntity.status(400);
                        body = ApiError.of(ErrorCatalog.INVALID_REQUEST, "The request is missing something or has an invalid part.");
                    }
                }
            }
            return reply.body(body);
        }
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCatalog.INTERNAL_ERROR, "Something went wrong on our side."));
    }

    private static ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCatalog.INVALID_REQUEST, message));
    }
}
