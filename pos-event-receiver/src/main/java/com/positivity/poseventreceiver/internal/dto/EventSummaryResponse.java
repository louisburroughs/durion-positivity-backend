package com.positivity.poseventreceiver.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Event type identifier", example = "ORDER_ORDER_CREATE")
        String eventTypeId,

        @Schema(description = "Number of events of this type in the timeframe", example = "42")
        long count) {}
