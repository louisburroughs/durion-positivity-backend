package com.positivity.people.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "People Reports API", description = "Reporting endpoints for people and attendance data")
@RestController
@RequestMapping("/v1/people/reports")
@RequiredArgsConstructor
public class PeopleReportsController {

    @Operation(summary = "Get attendance and job time discrepancy report", description = "Reporting endpoint for attendance vs. job time discrepancies.")
    @ApiResponse(responseCode = "200", description = "Report generated successfully.")
    @GetMapping("/attendanceJobtimeDiscrepancy")
    public ResponseEntity<Object> getAttendanceDiscrepancyReport() {
        log.info("Fetching attendance job time discrepancy report");
        // TODO: Implement attendance discrepancy report logic
        return ResponseEntity.ok().build();
    }
}
