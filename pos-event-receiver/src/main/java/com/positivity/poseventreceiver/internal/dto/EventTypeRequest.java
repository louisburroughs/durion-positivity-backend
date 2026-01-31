package com.positivity.poseventreceiver.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Unique code identifying the event type", example = "ORDER_CREATED")
    private String typeCode;

    @Schema(description = "Human-readable description of the event type", example = "Triggered when an order is created")
    private String description;

    @Schema(description = "Whether the event type is active", example = "true")
    private boolean active;

    @Schema(description = "API version for this event type", example = "1")
    private String apiVersion;

    @Schema(description = "50th percentile latency threshold in microseconds (default: 10,000,000 = 10s)", example = "500000")
    private Long p50Micros;

    @Schema(description = "95th percentile latency threshold in microseconds (default: 10,000,000 = 10s)", example = "1000000")
    private Long p95Micros;

    @Schema(description = "99th percentile latency threshold in microseconds (default: 10,000,000 = 10s)", example = "2000000")
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
