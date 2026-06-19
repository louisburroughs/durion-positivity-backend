package com.positivity.poseventreceiver.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for EventType API requests and responses.
 *
 * Includes optional latency percentile thresholds (p50, p95, p99) in
 * microseconds.
 * If not provided, defaults to 10 seconds (10,000,000 microseconds).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for creating or updating an EventType")
public class EventTypeRequest {

    @Schema(
            description = "Unique code identifying the event type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ORDER_CREATED")
    @NotBlank(message = "typeCode is required")
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "typeCode must contain only uppercase letters, digits, and underscores")
    private String typeCode;

    @Schema(
            description = "Human-readable description of the event type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Triggered when an order is created")
    @NotBlank(message = "description is required")
    private String description;

    @Schema(
            description = "Whether the event type is active",
            example = "true",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private boolean active;

    @Schema(
            description = "API version for this event type",
            example = "1",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Pattern(regexp = "^[0-9]+$", message = "apiVersion must be numeric")
    private String apiVersion;

    @Schema(
            description = "50th percentile latency threshold in microseconds (default: 10,000,000 = 10s)",
            example = "500000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Positive(message = "p50Micros must be positive")
    private Long p50Micros;

    @Schema(
            description = "95th percentile latency threshold in microseconds (default: 10,000,000 = 10s)",
            example = "1000000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Positive(message = "p95Micros must be positive")
    private Long p95Micros;

    @Schema(
            description = "99th percentile latency threshold in microseconds (default: 10,000,000 = 10s)",
            example = "2000000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Positive(message = "p99Micros must be positive")
    private Long p99Micros;

    public EventTypeRequest(String typeCode, String description) {
        this.typeCode = typeCode;
        this.description = description;
        this.active = true;
        this.apiVersion = "1";
    }

    public EventTypeRequest(String typeCode, String description, boolean active) {
        this.typeCode = typeCode;
        this.description = description;
        this.active = active;
        this.apiVersion = "1";
    }
}
