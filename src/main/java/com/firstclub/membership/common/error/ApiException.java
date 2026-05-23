package com.firstclub.membership.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base for domain-level exceptions that map to a specific HTTP status.
 * Carriers of a stable {@link #code} so clients can switch on it.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
