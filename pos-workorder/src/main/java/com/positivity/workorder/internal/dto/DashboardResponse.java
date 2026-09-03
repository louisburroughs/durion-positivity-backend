package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Daily Dispatch Board dashboard aggregate response")
public class DashboardResponse {
    @Schema(description = "The date this dashboard is for", example = "2026-01-15", requiredMode = REQUIRED)
    @NotNull
    LocalDate date;

    @Schema(
            description = "Location identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    String locationId;

    @Schema(description = "Workorder summaries for the day", example = "[]", requiredMode = REQUIRED)
    @NotNull
    List<WorkorderSummary> workorders;

    @Schema(description = "Mechanic statuses", example = "[]", requiredMode = NOT_REQUIRED)
    List<MechanicStatus> mechanics;

    @Schema(
            description = "Service bay statuses — every active bay at the location, including idle ones",
            example = "[]",
            requiredMode = NOT_REQUIRED)
    List<BayStatus> bays;

    @Schema(
            description = "Mobile service unit statuses — every active mobile unit based at the location, "
                    + "including idle ones. Reported alongside bays, not instead of them: a shop can dispatch "
                    + "to both.",
            example = "[]",
            requiredMode = NOT_REQUIRED)
    List<MobileUnitStatus> mobileUnits;

    @Schema(description = "Detected conflicts", example = "[]", requiredMode = NOT_REQUIRED)
    List<ConflictEntry> conflicts;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(
            description = "Timestamp when data was last refreshed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    Instant lastRefreshed;

    @Schema(
            description =
                    "True when one or more upstream data sources were unavailable during aggregation; data may be incomplete",
            example = "false",
            requiredMode = NOT_REQUIRED)
    Boolean dataQualityWarning;
}
