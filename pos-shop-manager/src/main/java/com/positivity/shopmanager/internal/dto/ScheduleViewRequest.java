package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Schema(description = "Request parameters for loading a schedule view for a location and date")
@Data
public class ScheduleViewRequest {

    @Schema(
            description = "Location identifier whose schedule is requested",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(description = "Calendar date of the schedule view (ISO-8601)", example = "2026-06-18", requiredMode = REQUIRED)
    private LocalDate date;

    @Schema(
            description = "Optional resource type filter (e.g. MECHANIC or BAY)",
            example = "MECHANIC",
            requiredMode = NOT_REQUIRED)
    private String resourceType;

    @Schema(
            description = "Optional specific resource identifier filter",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String resourceId;

    @Schema(
            description = "Whether to include the availability overlay in the response",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private boolean includeAvailabilityOverlay;

    @Schema(
            description = "Optional view range hint (e.g. DAY or WEEK)",
            example = "DAY",
            requiredMode = NOT_REQUIRED)
    private String range;
}
