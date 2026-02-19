package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "Attendance vs job time discrepancy row")
public record AttendanceDiscrepancyReportResponse(
                @Schema(description = "Technician identifier", example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = Schema.RequiredMode.REQUIRED) String technicianId,

                @Schema(description = "Technician display name", example = "Jane Doe", requiredMode = Schema.RequiredMode.REQUIRED) String technicianName,

                @Schema(description = "Location identifier", example = "22222222-2222-2222-2222-222222222222", requiredMode = Schema.RequiredMode.REQUIRED) String locationId,

                @Schema(description = "Report local date", example = "2026-02-16", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate reportDate,

                @Schema(description = "Total attendance time in decimal hours", example = "8.00", requiredMode = Schema.RequiredMode.REQUIRED) double totalAttendanceHours,

                @Schema(description = "Total approved job time in decimal hours", example = "6.00", requiredMode = Schema.RequiredMode.REQUIRED) double totalJobHours,

                @Schema(description = "Attendance minus job time in decimal hours", example = "2.00", requiredMode = Schema.RequiredMode.REQUIRED) double discrepancyHours,

                @Schema(description = "True when absolute discrepancy exceeds threshold", example = "true", requiredMode = Schema.RequiredMode.REQUIRED) boolean isFlagged,

                @Schema(description = "Threshold in minutes used for this row", example = "60", requiredMode = Schema.RequiredMode.REQUIRED) int thresholdApplied) {
}
