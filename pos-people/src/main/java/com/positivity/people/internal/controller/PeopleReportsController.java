package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.ApprovedTimeExportResponse;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.PeopleReportsService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "People Reports API", description = "Reporting endpoints for people and attendance data")
@RestController
@RequestMapping("/v1/people/reports")
@RequiredArgsConstructor
public class PeopleReportsController {

    private final PeopleReportsService peopleReportsService;

    @Operation(
            operationId = "getAttendanceDiscrepancyReport",
            summary = "Get Attendance Versus Job Time Discrepancy Report",
            description = """
                    Generates a per-technician, per-location, per-day report comparing attendance minutes from time \
                    entries with job minutes from the workorder job-time replica.
                    Use this tool to spot technicians whose clocked attendance diverges from booked job time; use \
                    listApprovedTimeForExport instead for the raw approved rows consumed by accounting export.
                    Preconditions: attendance comes from local time entries and job minutes from the \
                    ext_workorder_job_time replica, so very recent workorder activity may not be reflected yet.
                    Required inputs: startDate and endDate (inclusive, yyyy-MM-dd) and timezone (IANA, used to bucket \
                    minutes into local days); locationId and technicianIds are optional filters, and flaggedOnly \
                    defaults to false.
                    Emits a REPORT_ATTENDANCE_VS_JOBTIME_GENERATED audit event but changes no state; rows are \
                    flagged when the absolute discrepancy exceeds the location's configured threshold minutes.
                    Returns 400 when endDate is before startDate or timezone is not a valid IANA zone.
                    """)
    @ApiResponse(responseCode = "200", description = "Report generated successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid parameters",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Dependent service unavailable",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Unexpected server error",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "REPORT_ATTENDANCE_VS_JOBTIME_GENERATED", apiVersion = "1")
    @GetMapping("/attendanceJobtimeDiscrepancy")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:time:export"})
    @PreAuthorize("hasAnyAuthority('" + PeoplePermissions.ACCOUNTING_TIME_EXPORT + "')")
    public ResponseEntity<List<AttendanceDiscrepancyReportResponse>> getAttendanceDiscrepancyReport(
            @Parameter(description = "Start date (inclusive)", required = true, example = "2026-02-01") @RequestParam
                    LocalDate startDate,
            @Parameter(description = "End date (inclusive)", required = true, example = "2026-02-07") @RequestParam
                    LocalDate endDate,
            @Parameter(description = "IANA timezone", required = true, example = "America/Chicago") @RequestParam
                    String timezone,
            @Parameter(description = "Optional location filter") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Optional technician IDs filter") @RequestParam(required = false)
                    List<UUID> technicianIds,
            @Parameter(description = "Return flagged rows only") @RequestParam(defaultValue = "false")
                    boolean flaggedOnly,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        List<UUID> effectiveTechnicianIds = technicianIds == null ? List.of() : technicianIds;

        return ResponseEntity.ok(peopleReportsService.getAttendanceDiscrepancyReport(
                startDate, endDate, timezone, locationId, effectiveTechnicianIds, flaggedOnly, actorId, correlationId));
    }

    @Operation(
            operationId = "listApprovedTimeForExport",
            summary = "List Approved Time For Accounting Export",
            description = """
                    Returns APPROVED time-entry rows with worked hours, approval metadata, and resolved employee and \
                    location names for a date range and one or more locations.
                    Use this tool as the stable source-data read for accounting time-export workflows; use \
                    getAttendanceDiscrepancyReport instead for variance analysis between attendance and job time.
                    Preconditions: every supplied locationId must resolve to an active location; rows missing \
                    approval or attendance timestamps are silently excluded.
                    Required inputs: startDate and endDate (inclusive, yyyy-MM-dd, evaluated in UTC) and one or more \
                    locationId query parameters.
                    Emits a PEOPLE_TIME_APPROVED_EXPORT_READ audit event but changes no state; this is a read-only \
                    projection sorted by entry date then time entry id.
                    Returns 400 when endDate is before startDate, when no locationId is supplied, or when a \
                    locationId is unknown or inactive.
                    """)
    @ApiResponse(responseCode = "200", description = "Approved time rows retrieved successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid parameters",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Dependent service unavailable",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Unexpected server error",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_TIME_APPROVED_EXPORT_READ", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:time:export"})
    @PreAuthorize("hasAnyAuthority('" + PeoplePermissions.ACCOUNTING_TIME_EXPORT + "')")
    @GetMapping("/approvedTime")
    public ResponseEntity<List<ApprovedTimeExportResponse>> getApprovedTimeForExport(
            @Parameter(description = "Start date (inclusive)", required = true, example = "2026-02-01") @RequestParam
                    LocalDate startDate,
            @Parameter(description = "End date (inclusive)", required = true, example = "2026-02-07") @RequestParam
                    LocalDate endDate,
            @Parameter(description = "One or more location IDs", required = true) @RequestParam("locationId")
                    List<UUID> locationIds,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");

        List<ApprovedTimeExportResponse> rows = peopleReportsService.getApprovedTimeForExport(
                startDate, endDate, locationIds == null ? List.of() : locationIds, actorId, correlationId);

        return ResponseEntity.ok(rows);
    }
}
