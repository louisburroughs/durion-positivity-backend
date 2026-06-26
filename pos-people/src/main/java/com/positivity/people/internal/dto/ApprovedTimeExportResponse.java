package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Approved time entry row for downstream accounting export workflows.
 *
 * <p>
 * This is a People-domain source-of-truth read contract and intentionally carries
 * timekeeping identifiers. Payroll/accounting identifier mapping is performed by the
 * accounting domain.
 * </p>
 *
 * Issue: #79
 */
@Schema(description = "Approved time row for accounting/payroll export orchestration")
public record ApprovedTimeExportResponse(
        @Schema(
                description = "Time entry identifier",
                example = "01960011-0000-7000-8000-000000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String timeEntryId,

        @Schema(
                description = "Timekeeping employee identifier",
                example = "EMP-0001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String employeeId,

        @Schema(
                description = "Employee display name",
                example = "Jane Smith",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String employeeName,

        @Schema(
                description = "Timekeeping location identifier",
                example = "01960011-0000-7000-8000-000000000010",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID locationId,

        @Schema(
                description = "Location display name",
                example = "Downtown Service Center",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String locationName,

        @Schema(description = "Entry date (UTC)", example = "2026-02-16", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate entryDate,

        @Schema(
                description = "Worked hours in decimal format",
                example = "8.50",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal hoursWorked,

        @Schema(
                description = "Approval timestamp",
                example = "2026-02-17T09:15:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant approvedAt,

        @Schema(
                description = "Approver reference",
                example = "manager.user",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String approvedBy) {}
