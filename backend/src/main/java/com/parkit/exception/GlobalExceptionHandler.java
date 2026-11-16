package com.parkit.exception;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into RFC 7807 {@link ProblemDetail} responses
 * (ARCHITECTURE.md §7). Responses carry a stable {@code errorCode} and never
 * expose stack traces, SQL or other internals.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives ProblemDetail
 * responses for standard Spring MVC errors (malformed JSON, unsupported
 * method, missing parameters, ...).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** PostgreSQL SQLSTATE for exclusion-constraint violation (overlap). */
    static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";
    /** PostgreSQL SQLSTATE for unique-constraint violation. */
    static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** All ParkIt business exceptions, handled through their common base type. */
    @ExceptionHandler(ParkItException.class)
    public ProblemDetail handleParkItException(ParkItException ex) {
        return problem(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
    }

    /** Failed login (wrong email/password or deactivated account) -> 401, one generic message. */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
    }

    /** Access denied raised inside a controller or service -> 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission for this operation.");
    }

    /** Request-body validation failures (@Valid on DTOs) → 400 with field errors. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        List<String> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(e -> e.getDefaultMessage())
                .toList();

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "One or more fields are invalid.");
        body.setProperty("fieldErrors", fieldErrors);
        if (!globalErrors.isEmpty()) {
            body.setProperty("globalErrors", globalErrors);
        }
        return ResponseEntity.badRequest().body(body);
    }

    /** Unknown sort property in a paginated request -> 400. */
    @ExceptionHandler(org.springframework.data.mapping.PropertyReferenceException.class)
    public ProblemDetail handleBadSortProperty(org.springframework.data.mapping.PropertyReferenceException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Unknown sort property.");
    }

    /** Validation of path/query parameters (@Validated controllers) → 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> violations = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                violations.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "One or more parameters are invalid.");
        body.setProperty("fieldErrors", violations);
        return body;
    }

    /**
     * Database safety-net violations (DATABASE_DESIGN.md §6): exclusion
     * (23P01) and unique (23505) violations, and other integrity errors → 409.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = findSqlState(ex);
        log.warn("Data integrity violation (SQLSTATE {}): {}", sqlState, ex.getMostSpecificCause().getMessage());
        if (SQLSTATE_EXCLUSION_VIOLATION.equals(sqlState)) {
            return problem(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                    "The request conflicts with an existing reservation. Please retry.");
        }
        if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            return problem(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE",
                    "The resource already exists.");
        }
        return problem(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request violates a data integrity rule.");
    }

    /** Lock timeouts (5 s, ARCHITECTURE.md §6) → 409 "please retry". */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Lock acquisition failed: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                "The resource is busy. Please retry.");
    }

    /** Anything unexpected → 500 without internals; details go to the log only. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatusCode status, String errorCode, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setProperty("errorCode", errorCode);
        return body;
    }

    /** Walks the cause chain to find the JDBC SQLSTATE, if any. */
    static String findSqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
