package com.positivity.location.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A request refused because of one named field. {@code LocationGlobalExceptionHandler} renders it
 * as an {@code ApiError} whose {@code code} is {@link #getCode()} and whose {@code fieldErrors}
 * name {@link #getField()}, so a client can point at the offending input rather than guess from
 * the status (#2252).
 *
 * <p>Two shapes: {@link #invalid} for a malformed or out-of-range value (400
 * {@code VALIDATION_ERROR}), and {@link #unknownReference} for a well-formed id that resolves to
 * nothing (422 with a {@code *_NOT_FOUND} code, since the resource addressed by the path does
 * exist). The reason is the human-readable message; it names the field and never echoes the
 * rejected value (SonarCloud S5131).
 */
public class InvalidFieldException extends ResponseStatusException {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

    private final String code;
    private final String field;

    private InvalidFieldException(HttpStatus status, String code, String field, String message) {
        super(status, message);
        this.code = code;
        this.field = field;
    }

    /** 400 {@code VALIDATION_ERROR}: the value in {@code field} is malformed or out of range. */
    public static InvalidFieldException invalid(String field, String message) {
        return new InvalidFieldException(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, field, message);
    }

    /** 422 {@code code}: the id in {@code field} is well-formed but names nothing. */
    public static InvalidFieldException unknownReference(String code, String field, String message) {
        return new InvalidFieldException(HttpStatus.UNPROCESSABLE_ENTITY, code, field, message);
    }

    /** Machine-readable error code for {@code ApiError.code}. */
    public String getCode() {
        return code;
    }

    /** The request field at fault, as the client sent it (for example {@code coverageRules[1].ruleType}). */
    public String getField() {
        return field;
    }
}
