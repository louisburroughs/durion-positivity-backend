package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Value
@Builder
@Schema(description = "Daily Dispatch Board dashboard aggregate response")
public class DashboardResponse {
    @Schema(description = "The date this dashboard is for")
    LocalDate date;

    @Schema(description = "Location identifier")
    String locationId;

    @Schema(description = "Workorder summaries for the day")
    List<WorkorderSummary> workorders;

    @Schema(description = "Mechanic statuses")
    List<MechanicStatus> mechanics;

    @Schema(description = "Service bay statuses")
    List<BayStatus> bays;

    @Schema(description = "Detected conflicts")
    List<ConflictEntry> conflicts;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "Timestamp when data was last refreshed")
    Instant lastRefreshed;
}