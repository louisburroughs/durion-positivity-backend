package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.DashboardService;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"workorder:dashboard:view"})
@RequestMapping("/v1/workexec/dashboard")
@RequiredArgsConstructor
@Tag(name = "Daily Dispatch Board Dashboard", description = "Dispatch board aggregation and conflict detection")
public class DashboardController {

    private final DashboardService dashboardService;
    private final Clock clock;

    @GetMapping("/today")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.DASHBOARD_VIEW + "')")
    @EmitEvent(id = "WORKEXEC_DASHBOARD_TODAY_GET", apiVersion = "1")
    @Operation(operationId = "getDispatchDashboard", summary = "Get Daily Dispatch Board Dashboard", description = """
                    Returns the aggregated dispatch board for one location and one date: workorder summaries, \
                    mechanic statuses, bay statuses, mobile unit statuses, and detected scheduling conflicts.
                    Bays and mobile units are reported in separate arrays because they are separate kinds of \
                    resource; each array lists every active unit of its kind at the location, including units \
                    with no work today, which report assignedWorkorderId null, and a unit reads as occupied \
                    only while its assigned workorder is still open — a cancelled workorder, or a completed \
                    one that has not been reopened, releases the unit.
                    Use this tool when rendering the shop's daily dispatch board; do not use listWipWorkorders, \
                    which returns flat work-in-progress rows without mechanic, bay, or conflict aggregation.
                    Preconditions: the location must exist as a UUID-keyed location; mechanic availability comes \
                    from local people replicas, and a failed replica lookup sets dataQualityWarning to true \
                    instead of failing the call. Bay and mobile unit identity is served from local replicas of \
                    the location domain, so a unit whose replica row has not arrived yet is listed by id with a \
                    null name rather than being omitted.
                    Required inputs: locationId (UUID as a string) as a query parameter; date (ISO date) is \
                    optional and defaults to today on the server clock.
                    Emits a WORKEXEC_DASHBOARD_TODAY_GET audit event; no workorder state changes — this is a \
                    read-only aggregation.
                    Returns 400 when locationId does not parse as a UUID, and 200 with empty workorder and \
                    conflict panels when no workorders are scheduled for the date.
                    """)
    public ResponseEntity<DashboardResponse> getDashboard(
            @RequestParam String locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(dashboardService.getDashboard(locationId, effectiveDate));
    }
}
