package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.AppointmentsService;
import com.positivity.shopmanager.internal.service.ScheduleCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Schedule API", description = "Read-only schedule view operations for shop management UI")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"shop:schedule:view"})
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ScheduleController {

    private static final String LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds shop:schedule:view but its location scope does not cover the requested location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

    private final AppointmentsService appointmentsService;
    private final ScheduleCapacityService scheduleCapacityService;

    @Operation(operationId = "viewSchedule", summary = "View the Daily Schedule for a Location", description = """
                    Builds the read-only schedule board for one location and date, grouping appointments into \
                    resource lanes (bay, mobile unit, technician or UNASSIGNED) and marking overlaps of one minute \
                    or more within the same lane as BLOCKING conflicts.
                    Use this tool when rendering or inspecting a day's shop schedule; use getAppointmentById \
                    instead when a single appointment id is already known.
                    Preconditions: the location must exist as a shop; the day window is computed in the shop's \
                    configured timezone, falling back to UTC when none is configured.
                    Required inputs: locationId (UUID) and date (YYYY-MM-DD); resourceType and resourceId are \
                    optional filters, includeAvailabilityOverlay defaults to false, and range defaults to \
                    LOCATION_HOURS (06:00-18:00 local) with FULL_DAY covering midnight to midnight.
                    Emits a SHOPMGR_SCHEDULE_VIEW audit event; no state changes occur, and when the overlay is \
                    requested availabilityOverlayStatus reports AVAILABLE or UNAVAILABLE with an \
                    HR_SYSTEM_UNAVAILABLE warning when the staffing replica has no data for the location.
                    A caller whose shop:schedule:view grant is location-scoped must have locationId within reach \
                    (ADR-0061).
                    Returns 403 LOCATION_SCOPE_DENIED when the caller's location scope does not cover locationId, \
                    and 404 when the location is unknown or the resourceId filter matches no lane on that date.
                    """)
    @ApiResponse(responseCode = "200", description = "Schedule retrieved successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid input parameters")
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Location or resource not found")
    @GetMapping("/schedules/view")
    @EmitEvent(id = "SHOPMGR_SCHEDULE_VIEW", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + ShopPermissions.SCHEDULE_VIEW + "')")
    public ResponseEntity<ScheduleViewResponse> viewSchedule(
            @Parameter(description = "Location ID", required = true) @RequestParam UUID locationId,
            @Parameter(description = "Date in YYYY-MM-DD", required = true) @RequestParam LocalDate date,
            @Parameter(description = "Optional resource type filter") @RequestParam(required = false)
                    String resourceType,
            @Parameter(description = "Optional single resource filter") @RequestParam(required = false)
                    String resourceId,
            @Parameter(description = "Include HR availability overlay", required = false)
                    @RequestParam(defaultValue = "false")
                    boolean includeAvailabilityOverlay,
            @Parameter(description = "Schedule window range", required = false)
                    @RequestParam(defaultValue = "LOCATION_HOURS")
                    String range,
            @Parameter(description = "Correlation ID for request tracing")
                    @RequestHeader(value = "X-Correlation-Id", required = false)
                    UUID correlationId) {
        // locationId names the board being read; a scoped caller must have it in reach
        // (ADR-0061 §3, #1872). Spring has already rejected a malformed id with a 400.
        SecurityContextHelper.locationScope().require(ShopPermissions.SCHEDULE_VIEW, locationId);
        ScheduleViewRequest request = new ScheduleViewRequest();
        request.setLocationId(locationId);
        request.setDate(date);
        request.setResourceType(resourceType);
        request.setResourceId(resourceId);
        request.setIncludeAvailabilityOverlay(includeAvailabilityOverlay);
        request.setRange(range);
        log.info(
                "Schedule view requested. locationId={}, date={}, resourceType={}, resourceId={}, includeAvailabilityOverlay={}, range={}, X-Correlation-Id={}",
                locationId,
                date,
                resourceType,
                resourceId,
                includeAvailabilityOverlay,
                range,
                correlationId);
        ScheduleViewResponse response = appointmentsService.getScheduleView(request, correlationId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "getScheduleCapacity",
            summary = "Get per-day, per-bay occupancy for a location across a date range",
            description = """
                    Returns, for every date in [from, to], the location's day status (OK, CLOSED, HOLIDAY or \
                    UNAVAILABLE) and, on an OK date, every active bay with its occupied-minutes total and an \
                    hourly occupancy count array — never appointment identifiers, customer snapshots, titles or \
                    conflict details.
                    Use this tool to render a week or month capacity calendar in one call; use viewSchedule \
                    instead when a single day's full appointment board, including conflicts, is needed.
                    Preconditions: the day window and hours come from the location's replicated timezone and \
                    weekly operating hours (fed by pos-location facts), not from this module's Shop.timezone \
                    column; a location this module has not yet replicated, or whose timezone is unknown or \
                    blank, reports every requested date UNAVAILABLE rather than assuming UTC.
                    Required inputs: locationId (UUID), from and to (YYYY-MM-DD, inclusive, to on or after \
                    from), where the span must not exceed 42 days — a longer span, including a full year, is \
                    rejected.
                    Emits exactly one SHOPMGR_SCHEDULE_CAPACITY_VIEW audit event per call, never one per day; no \
                    state changes occur.
                    A bay with zero appointments on a date is still listed with occupiedMinutes 0 — free capacity \
                    is the reason this endpoint exists — and a date is never omitted from the response, even when \
                    it cannot be assembled.
                    A caller whose shop:schedule:view grant is location-scoped must have locationId within \
                    reach (ADR-0061).
                    Returns 400 when locationId, from or to is malformed or to is before from, 403 \
                    LOCATION_SCOPE_DENIED when the caller's location scope does not cover locationId, and 422 \
                    CAPACITY_RANGE_EXCEEDED when the span exceeds 42 days.
                    """)
    @ApiResponse(responseCode = "200", description = "Capacity view retrieved successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "locationId, from or to is malformed, or to is before from.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "The [from, to] span exceeds the 42-day policy limit (ApiError.code " + "CAPACITY_RANGE_EXCEEDED).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/schedules/capacity")
    @EmitEvent(id = "SHOPMGR_SCHEDULE_CAPACITY_VIEW", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + ShopPermissions.SCHEDULE_VIEW + "')")
    public ResponseEntity<ScheduleCapacityResponse> getScheduleCapacity(
            @Parameter(description = "Location ID", required = true) @RequestParam UUID locationId,
            @Parameter(description = "First date in the range, inclusive (YYYY-MM-DD)", required = true) @RequestParam
                    LocalDate from,
            @Parameter(description = "Last date in the range, inclusive (YYYY-MM-DD)", required = true) @RequestParam
                    LocalDate to,
            @Parameter(description = "Correlation ID for request tracing")
                    @RequestHeader(value = "X-Correlation-Id", required = false)
                    UUID correlationId) {
        // locationId names the board being read; a scoped caller must have it in reach
        // (ADR-0061 §3, #1872). Spring has already rejected a malformed id with a 400.
        SecurityContextHelper.locationScope().require(ShopPermissions.SCHEDULE_VIEW, locationId);
        log.info(
                "Schedule capacity requested. locationId={}, from={}, to={}, X-Correlation-Id={}",
                locationId,
                from,
                to,
                correlationId);
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, from, to);
        return ResponseEntity.ok(response);
    }
}
