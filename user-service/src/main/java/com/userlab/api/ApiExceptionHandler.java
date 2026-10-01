package com.userlab.api;

import com.userlab.support.ApiErrors.*;
import org.postgresql.util.PSQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every error is application/problem+json (RFC 9457). Extra properties:
 *   400 errors[] {field, message, rejectedValue}
 *   409 code (+ constraint, sqlState when the database rejected the write)
 *   412 currentVersion, currentETag
 *   422 code, field
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(fe -> error(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ValidationFailed.class)
    ProblemDetail validation(ValidationFailed ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage());
        pd.setProperty("errors", ex.errors());
        return pd;
    }

    @ExceptionHandler(NotFound.class)
    ProblemDetail notFound(NotFound ex) { return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage()); }

    @ExceptionHandler(Gone.class)
    ProblemDetail gone(Gone ex) { return problem(HttpStatus.GONE, "Gone", ex.getMessage()); }

    @ExceptionHandler(Conflict.class)
    ProblemDetail conflict(Conflict ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
        pd.setProperty("code", ex.code());
        return pd;
    }

    @ExceptionHandler(PreconditionFailed.class)
    ProblemDetail preconditionFailed(PreconditionFailed ex) {
        ProblemDetail pd = problem(HttpStatus.PRECONDITION_FAILED, "Precondition failed", ex.getMessage());
        if (ex.currentVersion() > 0) {
            pd.setProperty("currentVersion", ex.currentVersion());
            pd.setProperty("currentETag", UserController.etag(ex.currentVersion()));
        }
        return pd;
    }

    @ExceptionHandler(PreconditionRequired.class)
    ProblemDetail preconditionRequired(PreconditionRequired ex) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "Precondition required", ex.getMessage());
    }

    @ExceptionHandler(BusinessRule.class)
    ProblemDetail businessRule(BusinessRule ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated", ex.getMessage());
        pd.setProperty("code", ex.code());
        pd.setProperty("field", ex.field());
        return pd;
    }

    /** The database is the last line of defence. Report which constraint fired. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        String sqlState = null, constraint = null;
        if (root instanceof PSQLException pe) {
            sqlState = pe.getSQLState();
            if (pe.getServerErrorMessage() != null) constraint = pe.getServerErrorMessage().getConstraint();
        }
        String detail = switch (constraint == null ? "" : constraint) {
            case "uq_users_username" -> "Username is already taken";
            case "uq_users_email_lower" -> "Email is already registered (the check ignores upper/lower case)";
            case "uq_addresses_one_primary" -> "A user can have only one primary address";
            default -> "The database rejected the change";
        };
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Conflict", detail);
        pd.setProperty("code", "DB_CONSTRAINT");
        pd.setProperty("constraint", constraint);
        pd.setProperty("sqlState", sqlState);
        return pd;
    }

    /** Anything unexpected: 500 in the same shape, without SQL or stack traces. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected server error. See the service log.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }

    private static Map<String, Object> error(String field, String message, Object rejected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("message", message);
        m.put("rejectedValue", rejected);
        return m;
    }
}
