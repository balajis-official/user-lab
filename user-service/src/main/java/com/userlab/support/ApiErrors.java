package com.userlab.support;

import java.util.List;
import java.util.Map;

/** All custom exceptions in one file. Each one maps to exactly one HTTP status. */
public final class ApiErrors {
    private ApiErrors() {}

    /** 400: one or more fields are wrong on their own. */
    public static class ValidationFailed extends RuntimeException {
        private final List<Map<String, Object>> errors;
        public ValidationFailed(List<Map<String, Object>> errors) { super("Request validation failed"); this.errors = errors; }
        public List<Map<String, Object>> errors() { return errors; }
    }

    /** 404 */
    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    /** 410: the user existed but was deleted. Different from 404 (never existed). */
    public static class Gone extends RuntimeException {
        public Gone(String message) { super(message); }
    }

    /** 409: the current state does not allow this change. */
    public static class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** 412: If-Match did not match the current version. Someone else changed the user first. */
    public static class PreconditionFailed extends RuntimeException {
        private final int currentVersion;
        public PreconditionFailed(String message, int currentVersion) { super(message); this.currentVersion = currentVersion; }
        public int currentVersion() { return currentVersion; }
    }

    /** 428: the request needs an If-Match header and did not send one. */
    public static class PreconditionRequired extends RuntimeException {
        public PreconditionRequired(String message) { super(message); }
    }

    /** 422: every field is valid, but together they break a business rule. */
    public static class BusinessRule extends RuntimeException {
        private final String code;
        private final String field;
        public BusinessRule(String code, String field, String message) { super(message); this.code = code; this.field = field; }
        public String code() { return code; }
        public String field() { return field; }
    }
}
