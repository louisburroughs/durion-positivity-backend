package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
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

    private static final String LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds workorder:dashboard:view but its location scope does not cover the requested location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

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
                    while any still-open workorder holds it, including a multi-day job scheduled on an earlier \
                    date — a cancelled workorder, or a completed one that has not been reopened, releases it.
                    Use this tool when rendering the shop's daily dispatch board; do not use listWipWorkorders, \
                    which returns flat work-in-progress rows without mechanic, bay, or conflict aggregation.
                    Preconditions: the location must exist as a UUID-keyed location; mechanic availability comes \
                    from local people replicas, and a failed replica lookup sets dataQualityWarning to true \
                    instead of failing the call. Bay and mobile unit identity is served from local replicas of \
                    the location domain, so a unit whose replica row has not arrived yet is listed by id with a \
                    null name rather than being omitted.
                    Required inputs: locationId (UUID as a string) as a query parameter; date (ISO date) is \
                    optional and defaults to today on the server clock. A caller whose \
                    workorder:dashboard:view grant is location-scoped must have locationId within reach \
                    (ADR-0061).
                    Emits a WORKEXEC_DASHBOARD_TODAY_GET audit event; no workorder state changes — this is a \
                    read-only aggregation.
                    Returns 400 when locationId does not parse as a UUID, 403 LOCATION_SCOPE_DENIED when the \
                    caller's location scope does not cover locationId, and 200 with empty workorder and \
                    conflict panels when no workorders are scheduled for the date.
                    """)
    @ApiResponse(responseCode = "200", description = "Dispatch board for the location and date")
    @ApiResponse(
            responseCode = "400",
            description = "locationId does not parse as a UUID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<DashboardResponse> getDashboard(
            @RequestParam String locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        // Gate (ADR-0061 §3, #1872): the board is one location's. Validate before the scope check so
        // a malformed id is a 400 for every caller rather than a 403 for scoped callers only;
        // DashboardService re-parses, which keeps its query shape intact.
        UUID requestedLocation = parseLocationId(locationId);
        SecurityContextHelper.locationScope().require(WorkorderPermissions.DASHBOARD_VIEW, requestedLocation);

        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(dashboardService.getDashboard(locationId, effectiveDate));
    }

    private static UUID parseLocationId(String locationId) {
        try {
            return UUID.fromString(locationId);
        } catch (IllegalArgumentException e) {
            throw new WorkorderRequestValidationException("locationId is not a valid UUID: " + locationId);
        }
    }
}
