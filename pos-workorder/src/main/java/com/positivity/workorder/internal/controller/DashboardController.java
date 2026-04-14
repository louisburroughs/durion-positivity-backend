package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workexec/dashboard")
@RequiredArgsConstructor
@Tag(name = "Daily Dispatch Board Dashboard", description = "Dispatch board aggregation and conflict detection")
public class DashboardController {

    private final DashboardService dashboardService;
    private final Clock clock;

    @GetMapping("/today")
    @PreAuthorize("hasAuthority('workorder:dashboard:view')")
    @EmitEvent(id = "WORKEXEC_DASHBOARD_TODAY_GET", apiVersion = "1")
    @Operation(
            summary = "Get daily dispatch dashboard",
            description = "Returns workorder, mechanic, bay, and conflict data for the given location and date")
    public ResponseEntity<DashboardResponse> getDashboard(
            @RequestParam String locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(dashboardService.getDashboard(locationId, effectiveDate));
    }
}
