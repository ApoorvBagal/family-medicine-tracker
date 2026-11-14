package com.familymeds.shared.web;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An expected, client-facing error. Rendered as application/problem+json with a stable {@code code}
 * the frontend can switch on. The message must never contain secrets or internal details.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    public ApiException(HttpStatus status, String code, String detail, Duration retryAfter) {
        this(status, code, detail, retryAfter, Map.of());
    }

    /** {@code properties} are added to the problem+json body (e.g. the id of an existing duplicate). */
    public ApiException(HttpStatus status, String code, String detail, Duration retryAfter,
            Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
