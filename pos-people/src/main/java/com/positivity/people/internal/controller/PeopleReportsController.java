package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.positivity.people.internal.dto.ApprovedTimeExportResponse;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.service.PeopleReportsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "People Reports API", description = "Reporting endpoints for people and attendance data")
@RestController
@RequestMapping("/v1/people/reports")
@RequiredArgsConstructor
public class PeopleReportsController {

	private final PeopleReportsService peopleReportsService;

	@Operation(summary = "Get attendance and job time discrepancy report",
			description = "Generates a per-technician, per-location, per-day discrepancy report based on attendance and approved job time totals.")
	@ApiResponse(responseCode = "200", description = "Report generated successfully.")
	@ApiResponse(responseCode = "400", description = "Invalid parameters")
	@ApiResponse(responseCode = "403", description = "Forbidden")
	@ApiResponse(responseCode = "503", description = "Dependent service unavailable")
	@ApiResponse(responseCode = "500", description = "Unexpected server error")
	@EmitEvent(id = "REPORT_ATTENDANCE_VS_JOBTIME_GENERATED", apiVersion = "1")
	@GetMapping("/attendanceJobtimeDiscrepancy")
	@PreAuthorize("hasAnyAuthority('people:time:export:read','accounting:time:export')")
	public ResponseEntity<List<AttendanceDiscrepancyReportResponse>> getAttendanceDiscrepancyReport(
			@Parameter(description = "Start date (inclusive)", required = true,
					example = "2026-02-01") @RequestParam LocalDate startDate,
			@Parameter(description = "End date (inclusive)", required = true,
					example = "2026-02-07") @RequestParam LocalDate endDate,
			@Parameter(description = "IANA timezone", required = true,
					example = "America/Chicago") @RequestParam String timezone,
			@Parameter(description = "Optional location filter") @RequestParam(required = false) UUID locationId,
			@Parameter(description = "Optional technician IDs filter") @RequestParam(
					required = false) List<UUID> technicianIds,
			@Parameter(description = "Return flagged rows only") @RequestParam(
					defaultValue = "false") boolean flaggedOnly,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
		String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
		List<UUID> effectiveTechnicianIds = technicianIds == null ? List.of() : technicianIds;

		return ResponseEntity.ok(peopleReportsService.getAttendanceDiscrepancyReport(startDate, endDate, timezone,
				locationId, effectiveTechnicianIds, flaggedOnly, actorId, correlationId));
	}

	@Operation(summary = "Get approved time entries for accounting export",
			description = "Returns People-domain approved time rows for a date range and one or more locations. "
					+ "This endpoint is the stable source-data read contract for accounting export workflows.")
	@ApiResponse(responseCode = "200", description = "Approved time rows retrieved successfully")
	@ApiResponse(responseCode = "400", description = "Invalid parameters")
	@ApiResponse(responseCode = "403", description = "Forbidden")
	@ApiResponse(responseCode = "503", description = "Dependent service unavailable")
	@ApiResponse(responseCode = "500", description = "Unexpected server error")
	@EmitEvent(id = "PEOPLE_TIME_APPROVED_EXPORT_READ", apiVersion = "1")
	@PreAuthorize("hasAnyAuthority('people:time:export:read','accounting:time:export')")
	@GetMapping("/approvedTime")
	public ResponseEntity<List<ApprovedTimeExportResponse>> getApprovedTimeForExport(
			@Parameter(description = "Start date (inclusive)", required = true,
					example = "2026-02-01") @RequestParam LocalDate startDate,
			@Parameter(description = "End date (inclusive)", required = true,
					example = "2026-02-07") @RequestParam LocalDate endDate,
			@Parameter(description = "One or more location IDs",
					required = true) @RequestParam("locationId") List<UUID> locationIds,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
		String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");

		List<ApprovedTimeExportResponse> rows = peopleReportsService.getApprovedTimeForExport(startDate, endDate,
				locationIds == null ? List.of() : locationIds, actorId, correlationId);

		return ResponseEntity.ok(rows);
	}

}
