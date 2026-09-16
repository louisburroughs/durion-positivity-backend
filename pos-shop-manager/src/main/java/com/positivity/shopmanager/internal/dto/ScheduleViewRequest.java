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

    @Schema(
            description = "Calendar date of the schedule view (ISO-8601)",
            example = "2026-06-18",
            requiredMode = REQUIRED)
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
            description = "Optional day-window hint. Recognised values are FULL_DAY (the target date's "
                    + "midnight-to-midnight window in the location's timezone) and LOCATION_HOURS, the "
                    + "controller's own default. Despite its name, LOCATION_HOURS is a hardcoded 06:00-18:00 "
                    + "local window for every location and every date (#2023 F1) — no location's actual "
                    + "operating hours are consulted by this endpoint. Any value other than FULL_DAY, "
                    + "including an unrecognised one, silently degrades to that same 06:00-18:00 window.",
            example = "LOCATION_HOURS",
            requiredMode = NOT_REQUIRED)
    private String range;
}
