package com.positivity.poseventreceiver.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * DTO representing a count of events for a specific event type within a
 * timeframe.
 *
 * @param eventTypeId the event type identifier (e.g., ORDER_ORDER_CREATE)
 * @param count       the number of events of this type within the queried
 *                    timeframe
 */
@Schema(description = "Event count grouped by event type for a given timeframe")
public record EventSummaryResponse(
        @Schema(description = "Event type identifier", example = "ORDER_ORDER_CREATE", requiredMode = REQUIRED) @NotNull
        String eventTypeId,

        @Schema(description = "Number of events of this type in the timeframe", example = "42", requiredMode = REQUIRED)
        long count) {}
