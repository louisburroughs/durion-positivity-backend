package com.positivity.shared.error;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
        @Schema(description = "Machine-readable error code", example = "ORDER_NOT_FOUND", requiredMode = REQUIRED)
        @NotNull
        String code,

        @Schema(
                description = "Human-readable error message",
                example = "Order '550e8400-e29b-41d4-a716-446655440000' was not found",
                requiredMode = REQUIRED)
        @NotNull
        String message,

        @Schema(description = "HTTP status code", example = "404", requiredMode = REQUIRED)
        int status,

        @Schema(
                description = "ISO 8601 UTC timestamp of the error",
                example = "2026-03-17T14:30:00.000Z",
                requiredMode = REQUIRED)
        @NotNull
        String timestamp,

        @Schema(
                description = "Unique correlation ID for distributed request tracing",
                example = "550e8400-e29b-41d4-a716-446655440000",
                requiredMode = REQUIRED)
        @NotNull
        String correlationId,

        @Schema(
                description = "Field-level validation errors. Optional and module-dependent: a module populates it "
                        + "for validation-related errors such as VALIDATION_ERROR or VALIDATION_FAILED "
                        + "where doing so is safe, and omits it otherwise — some modules deliberately "
                        + "withhold it because the binding result names internal property names and the "
                        + "response is provokable by any caller. Never present for non-validation errors. "
                        + "Treat it as absent unless the endpoint's own documentation says otherwise",
                requiredMode = NOT_REQUIRED)
        List<FieldError> fieldErrors,

        @Schema(
                description = "Workflow or review-case reference identifier, when applicable",
                example = "REVIEW-2026-000123",
                requiredMode = NOT_REQUIRED)
        String referenceId,

        @Schema(
                description = "Recommended next step for the caller, when applicable",
                example = "Retry the request with a valid quantity",
                requiredMode = NOT_REQUIRED)
        String nextAction,

        @Schema(
                description = "Support or admin investigation guidance, when applicable",
                example = "Contact support with the correlation ID for assistance",
                requiredMode = NOT_REQUIRED)
        String supportAction,

        @Schema(
                description = "Itemized conflicts behind a 409 whose cause is a set of named conflicts, such as "
                        + "SCHEDULING_CONFLICT (ADR-0017 §3). Present only on such a 409; absent otherwise",
                requiredMode = NOT_REQUIRED)
        List<Conflict> conflicts,

        @Schema(
                description = "Alternatives the caller may retry with, accompanying conflicts when the service can "
                        + "compute them. Optional even when conflicts is present",
                requiredMode = NOT_REQUIRED)
        List<SuggestedAlternative> suggestedAlternatives) {

    /**
     * The nine-field envelope, without itemized conflicts. Kept so existing callers need not pass the
     * conflict fields (ADR-0017 §3).
     */
    public ApiError(
            String code,
            String message,
            int status,
            String timestamp,
            String correlationId,
            List<FieldError> fieldErrors,
            String referenceId,
            String nextAction,
            String supportAction) {
        this(
                code,
                message,
                status,
                timestamp,
                correlationId,
                fieldErrors,
                referenceId,
                nextAction,
                supportAction,
                null,
                null);
    }

    /**
     * Field-level validation error within an {@link ApiError}.
     */
    @Schema(description = "Validation error for a specific request field")
    public record FieldError(
            @Schema(description = "Field name", example = "quantity", requiredMode = REQUIRED) @NotNull
            String field,

            @Schema(
                    description = "Validation failure message",
                    example = "must be greater than 0",
                    requiredMode = REQUIRED)
            @NotNull
            String message) {}

    /**
     * One named conflict within a 409 {@link ApiError} (ADR-0017 §3). {@code severity} decides whether
     * the caller may override it: HARD conflicts cannot be overridden, SOFT conflicts can.
     */
    @Schema(description = "A single named conflict behind a 409 response")
    public record Conflict(
            @Schema(description = "Conflict severity (HARD or SOFT)", example = "SOFT", requiredMode = REQUIRED)
            @NotNull
            String severity,

            @Schema(
                    description = "Machine-readable conflict code",
                    example = "MECHANIC_UNAVAILABLE",
                    requiredMode = REQUIRED)
            @NotNull
            String code,

            @Schema(
                    description = "User-safe description of the conflict",
                    example = "The selected mechanic is already booked",
                    requiredMode = REQUIRED)
            @NotNull
            String message,

            @Schema(
                    description = "Whether the conflict can be overridden (HARD=false, SOFT=true)",
                    example = "true",
                    requiredMode = REQUIRED)
            boolean overridable,

            @Schema(
                    description = "Resource affected by the conflict",
                    example = "Mechanic: John Doe",
                    requiredMode = NOT_REQUIRED)
            String affectedResource) {}

    /** An alternative the caller may retry with, accompanying {@link ApiError#conflicts()}. */
    @Schema(description = "A suggested alternative accompanying a 409 conflict")
    public record SuggestedAlternative(
            @Schema(
                    description = "Suggested start time (ISO-8601 with offset)",
                    example = "2026-06-18T09:00:00-05:00",
                    requiredMode = REQUIRED)
            @NotNull
            String startDateTime,

            @Schema(
                    description = "Suggested end time (ISO-8601 with offset)",
                    example = "2026-06-18T10:00:00-05:00",
                    requiredMode = REQUIRED)
            @NotNull
            String endDateTime,

            @Schema(
                    description = "Reason this alternative is suggested",
                    example = "Mechanic available",
                    requiredMode = NOT_REQUIRED)
            String reason) {}

    /** Creates a 409 carrying itemized conflicts and, optionally, suggested alternatives. */
    public static ApiError withConflicts(
            String code,
            String message,
            int status,
            String timestamp,
            String correlationId,
            List<Conflict> conflicts,
            List<SuggestedAlternative> suggestedAlternatives) {
        return new ApiError(
                code,
                message,
                status,
                timestamp,
                correlationId,
                null,
                null,
                null,
                null,
                conflicts,
                suggestedAlternatives);
    }

    /** Creates an error with no optional fields. */
    public static ApiError of(String code, String message, int status, String timestamp, String correlationId) {
        return new ApiError(code, message, status, timestamp, correlationId, null, null, null, null);
    }

    /** Creates a validation error with field-level errors. */
    public static ApiError withFieldErrors(
            String code,
            String message,
            int status,
            String timestamp,
            String correlationId,
            List<FieldError> fieldErrors) {
        return new ApiError(code, message, status, timestamp, correlationId, fieldErrors, null, null, null);
    }

    /** Creates a guided error with next-action and support hints. */
    public static ApiError guided(
            String code,
            String message,
            int status,
            String timestamp,
            String correlationId,
            String referenceId,
            String nextAction,
            String supportAction) {
        return new ApiError(
                code, message, status, timestamp, correlationId, null, referenceId, nextAction, supportAction);
    }
}
