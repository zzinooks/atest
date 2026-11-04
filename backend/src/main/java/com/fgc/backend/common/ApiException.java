package com.fgc.backend.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found"); }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "No permission for this patient or action"); }
    public static ApiException badRequest(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", msg); }
    public static ApiException conflict(String msg) { return new ApiException(HttpStatus.CONFLICT, "conflict", msg); }
}
