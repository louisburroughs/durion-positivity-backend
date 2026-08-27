package com.positivity.poseventreceiver.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing one recorded emitted-event row, returned by the entity-indexed event
 * query endpoint (GET /v1/events).
 *
 * @param eventId     the emitted_event primary key
 * @param id          the event type identifier (e.g., ORDER_ORDER_CREATE)
 * @param apiVersion  the API version that triggered this event
 * @param timestamp   when the event completed, in epoch milliseconds
 * @param elapsedMs   elapsed time in milliseconds for the operation
 * @param publishedAt when the event was published
 * @param entityId    the entity id this event was recorded against
 */
@Schema(description = "One recorded emitted-event occurrence")
public record EmittedEventResponse(
        @Schema(description = "Emitted event row identifier", requiredMode = REQUIRED)
        UUID eventId,

        @Schema(description = "Event type identifier", example = "ORDER_ORDER_CREATE", requiredMode = REQUIRED)
        String id,

        @Schema(description = "Event API version", example = "1", requiredMode = REQUIRED)
        String apiVersion,

        @Schema(
                description = "Event timestamp in epoch milliseconds",
                example = "1730809200000",
                requiredMode = REQUIRED)
        long timestamp,

        @Schema(description = "Elapsed operation time in milliseconds", example = "42", requiredMode = REQUIRED)
        long elapsedMs,

        @Schema(description = "Event publication time in UTC", requiredMode = REQUIRED)
        Instant publishedAt,

        @Schema(
                description = "Entity id this event was recorded against",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                requiredMode = NOT_REQUIRED)
        String entityId) {}
