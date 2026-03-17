package com.positivity.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Canonical error response envelope for all Durion backend APIs.
 *
 * <p>All REST controllers should return this type on non-2xx responses,
 * so that API consumers have a consistent structure to parse.
 *
 * <p>See {@code docs/ERROR_ENVELOPE.md} for payload examples and field semantics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard error response envelope returned by all Durion backend APIs")
public record ApiError(
        @Schema(description = "Machine-readable error code", example = "ORDER_NOT_FOUND")
        String code,

        @Schema(description = "Human-readable error message",
                example = "Order '550e8400-e29b-41d4-a716-446655440000' was not found")
        String message,

        @Schema(description = "HTTP status code", example = "404")
        int status,

        @Schema(description = "ISO 8601 UTC timestamp of the error", example = "2026-03-17T14:30:00.000Z")
        String timestamp,

        @Schema(description = "Unique correlation ID for distributed request tracing",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String correlationId,

        @Schema(description = "Field-level validation errors; present (non-null) for validation-related errors such as VALIDATION_ERROR or VALIDATION_FAILED; omitted for all other error types")
        List<FieldError> fieldErrors,

        @Schema(description = "Workflow or review-case reference identifier, when applicable")
        String referenceId,

        @Schema(description = "Recommended next step for the caller, when applicable")
        String nextAction,

        @Schema(description = "Support or admin investigation guidance, when applicable")
        String supportAction) {

    /**
     * Field-level validation error within an {@link ApiError}.
     */
    @Schema(description = "Validation error for a specific request field")
    public record FieldError(
            @Schema(description = "Field name", example = "quantity")
            String field,
            @Schema(description = "Validation failure message", example = "must be greater than 0")
            String message) {
    }

    /** Creates an error with no optional fields. */
    public static ApiError of(String code, String message, int status, String timestamp, String correlationId) {
        return new ApiError(code, message, status, timestamp, correlationId, null, null, null, null);
    }

    /** Creates a validation error with field-level errors. */
    public static ApiError withFieldErrors(String code, String message, int status, String timestamp,
            String correlationId, List<FieldError> fieldErrors) {
        return new ApiError(code, message, status, timestamp, correlationId, fieldErrors, null, null, null);
    }

    /** Creates a guided error with next-action and support hints. */
    public static ApiError guided(String code, String message, int status, String timestamp, String correlationId,
            String referenceId, String nextAction, String supportAction) {
        return new ApiError(code, message, status, timestamp, correlationId, null, referenceId, nextAction,
                supportAction);
    }
}
