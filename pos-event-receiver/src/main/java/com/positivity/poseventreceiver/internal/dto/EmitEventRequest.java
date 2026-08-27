package com.positivity.poseventreceiver.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * DTO for emitting event requests via REST API.
 * This record is used for REST API calls communication
 * to ensure consistency between external and internal interfaces.
 */
@Schema(description = "Request payload used to emit and persist an application event")
public record EmitEventRequest(
        @Schema(
                description = "Preregistered event type code",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "ORDER_ORDER_CREATE")
        @NotBlank(message = "id is required")
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "id must contain only uppercase letters, digits, and underscores")
        String id,

        @Schema(description = "Event API version", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotBlank(message = "apiVersion is required")
        @Pattern(regexp = "^[0-9]+$", message = "apiVersion must be numeric")
        String apiVersion,

        @Schema(
                description = "Event timestamp in epoch milliseconds",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "1730809200000")
        @Positive(message = "timestamp must be positive")
        long timestamp,

        @Schema(
                description = "Elapsed operation time in milliseconds",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "42")
        @PositiveOrZero(message = "elapsedMs must be zero or positive")
        long elapsedMs,

        @Schema(
                description = "Event publication time in UTC",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "2026-03-05T21:15:00Z")
        @NotNull(message = "publishedAt is required")
        Instant publishedAt,

        @Schema(
                description = "Optional identifier of the entity this event relates to, enabling later lookup via"
                        + " GET /v1/events?entityId=...; omit when the event has no single associated entity",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        String entityId) {}
