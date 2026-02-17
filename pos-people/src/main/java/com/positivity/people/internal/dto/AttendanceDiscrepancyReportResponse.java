package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Attendance discrepancy report aggregating time entry approval statuses")
public record AttendanceDiscrepancyReportResponse(
        @Schema(description = "Timestamp when the report was generated", example = "2026-02-17T10:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant generatedAt,

        @Schema(description = "Count of approved time entries", example = "42", requiredMode = Schema.RequiredMode.REQUIRED) long approvedCount,

        @Schema(description = "Count of time entries pending approval", example = "7", requiredMode = Schema.RequiredMode.REQUIRED) long pendingApprovalCount,

        @Schema(description = "Count of rejected time entries", example = "3", requiredMode = Schema.RequiredMode.REQUIRED) long rejectedCount) {
}
