package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.service.PeopleReportsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "People Reports API", description = "Reporting endpoints for people and attendance data")
@RestController
@RequestMapping("/v1/people/reports")
@RequiredArgsConstructor
public class PeopleReportsController {

    private final PeopleReportsService peopleReportsService;

    @Operation(summary = "Get attendance and job time discrepancy report", description = "Generates a per-technician, per-location, per-day discrepancy report based on attendance and approved job time totals.")
    @ApiResponse(responseCode = "200", description = "Report generated successfully.")
    @EmitEvent(id = "REPORT_ATTENDANCE_VS_JOBTIME_GENERATED", apiVersion = "1")
    @GetMapping("/attendanceJobtimeDiscrepancy")
    public ResponseEntity<List<AttendanceDiscrepancyReportResponse>> getAttendanceDiscrepancyReport(
            @Parameter(description = "Start date (inclusive)", required = true, example = "2026-02-01") @RequestParam LocalDate startDate,
            @Parameter(description = "End date (inclusive)", required = true, example = "2026-02-07") @RequestParam LocalDate endDate,
            @Parameter(description = "IANA timezone", required = true, example = "America/Chicago") @RequestParam String timezone,
            @Parameter(description = "Optional location filter") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Optional technician IDs filter") @RequestParam(required = false) List<UUID> technicianIds,
            @Parameter(description = "Return flagged rows only") @RequestParam(defaultValue = "false") boolean flaggedOnly,
            @RequestHeader(value = "X-User", required = false) String managerId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        List<UUID> effectiveTechnicianIds = technicianIds == null ? List.of() : technicianIds;
        log.info(
                "Generating attendance job-time discrepancy report managerId={}, correlationId={}, startDate={}, endDate={}, timezone={}, locationId={}, technicianCount={}, flaggedOnly={}",
                managerId,
                correlationId,
                startDate,
                endDate,
                timezone,
                locationId,
                effectiveTechnicianIds.size(),
                flaggedOnly);

        return ResponseEntity.ok(peopleReportsService.getAttendanceDiscrepancyReport(
                startDate,
                endDate,
                timezone,
                locationId,
                effectiveTechnicianIds,
                flaggedOnly));
    }
}
