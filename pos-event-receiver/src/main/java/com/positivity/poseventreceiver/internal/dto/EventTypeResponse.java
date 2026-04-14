package com.positivity.poseventreceiver.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * DTO returned by EventType controller endpoints.
 */
@Schema(description = "Response payload representing a registered event type")
public record EventTypeResponse(
        @Schema(
                description = "Event type identifier",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
        UUID id,

        @Schema(
                description = "Unique event type code",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "ORDER_CREATED")
        String typeCode,

        @Schema(
                description = "Human-readable event type description",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "Triggered when an order is created")
        String description,

        @Schema(
                description = "Whether this event type is active",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "true")
        boolean active,

        @Schema(description = "Event API version", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        String apiVersion,

        @Schema(
                description = "p50 latency threshold in microseconds",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "500000")
        long p50Micros,

        @Schema(
                description = "p95 latency threshold in microseconds",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "1000000")
        long p95Micros,

        @Schema(
                description = "p99 latency threshold in microseconds",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "2000000")
        long p99Micros) {}
