package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.OpeningSearchQuery;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.dto.ScheduleViewRequest;
import com.positivity.shopmanager.internal.dto.ScheduleViewResponse;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.AppointmentsService;
import com.positivity.shopmanager.internal.service.OpeningSearchService;
import com.positivity.shopmanager.internal.service.ScheduleCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    private final OpeningSearchService openingSearchService;

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
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Location or resource not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                    Required inputs: locationId (UUID), and from and to (YYYY-MM-DD, inclusive, to on or after \
                    from) spanning at most 42 days — a longer span, including a full year, is rejected.
                    Emits exactly one SHOPMGR_SCHEDULE_CAPACITY_VIEW audit event per call, never one per day; no \
                    state changes occur. A bay with zero appointments on a date is still listed with \
                    occupiedMinutes 0 — free capacity is the reason this endpoint exists — and a date is never \
                    omitted from the response, even when it cannot be assembled.
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

    @Operation(
            operationId = "searchOpenings",
            summary = "Search duration-aware eligible openings for a job",
            description = """
                    Finds the windows at one location in which a whole job fits, unbroken, in one eligible bay, \
                    with a technician rostered that day and free in the window, honouring the location's check-in \
                    and cleanup buffers, and ranks them earliest start first with CERTIFIED openings before \
                    AWAITING ones at equal starts.
                    Use this tool when a service advisor asks when the next slot for a job of a given length is; \
                    use getScheduleCapacity instead for per-day, per-bay occupancy of a calendar range rather than \
                    bookable windows.
                    Preconditions: the location must be known to shop management with a recognised timezone and \
                    published operating hours, and every serviceId must be a catalog service known to shop management.
                    Required inputs: locationId (UUID), serviceIds (one to ten catalog service UUIDs), durationMinutes \
                    (1 to 1440) and earliestStart (ISO-8601 instant); vehicleId and technicianId are optional, \
                    horizonDays defaults to 30 (the maximum) and limit defaults to 10 (maximum 50).
                    Emits a SHOPMGR_SCHEDULE_OPENING_SEARCH audit event and changes no state; the search is advisory \
                    and the submit-time conflict evaluation on appointment creation remains authoritative, which is \
                    why every opening lists constraintsEvaluated.
                    Skill never withholds an opening: a technician lacking a required skill makes the opening AWAITING, \
                    and nobody competent rostered in the horizon is reported once as staffingAdvisory alongside the \
                    openings rather than as a noOpeningReason, which names only NO_ELIGIBLE_BAY_AT_LOCATION or \
                    ALL_ELIGIBLE_BAYS_BOOKED.
                    Returns 400 for a malformed or out-of-range value, 403 LOCATION_SCOPE_DENIED when the caller's \
                    location scope does not cover locationId, 404 when the location or a service is unknown, and 422 \
                    OPENING_HORIZON_EXCEEDED, OPENING_LIMIT_EXCEEDED, OPENING_TOO_MANY_SERVICES or \
                    LOCATION_HOURS_UNKNOWN when a policy bound or a facility fact is not met.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Openings computed; the list may be empty with noOpeningReason set.")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed or out-of-range input",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Location or catalog service unknown to shop management",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "A policy bound exceeded or the location's hours unpublished (ApiError.code"
                    + " OPENING_HORIZON_EXCEEDED, OPENING_LIMIT_EXCEEDED, OPENING_TOO_MANY_SERVICES,"
                    + " LOCATION_HOURS_UNKNOWN).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/schedules/openings")
    @EmitEvent(id = "SHOPMGR_SCHEDULE_OPENING_SEARCH", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + ShopPermissions.SCHEDULE_VIEW + "')")
    public ResponseEntity<OpeningSearchResponse> searchOpenings(
            @Parameter(description = "Location ID", required = true) @RequestParam UUID locationId,
            @Parameter(description = "Catalog service ids the job consists of (1-10)", required = true) @RequestParam
                    List<UUID> serviceIds,
            @Parameter(description = "Job duration in minutes (1-1440)", required = true) @RequestParam
                    int durationMinutes,
            @Parameter(description = "No opening starts before this instant (ISO-8601)", required = true) @RequestParam
                    Instant earliestStart,
            @Parameter(description = "Vehicle whose GVWR class narrows bays and skill requirements")
                    @RequestParam(required = false)
                    UUID vehicleId,
            @Parameter(description = "Restrict openings to ones this technician (person id) can take")
                    @RequestParam(required = false)
                    UUID technicianId,
            @Parameter(description = "Facility-local days to search forward from earliestStart (1-30)")
                    @RequestParam(required = false, defaultValue = "30")
                    int horizonDays,
            @Parameter(description = "Most openings to return (1-50)")
                    @RequestParam(required = false, defaultValue = "10")
                    int limit,
            @Parameter(description = "Correlation ID for request tracing")
                    @RequestHeader(value = "X-Correlation-Id", required = false)
                    UUID correlationId) {
        SecurityContextHelper.locationScope().require(ShopPermissions.SCHEDULE_VIEW, locationId);
        log.info(
                "Opening search requested. locationId={}, serviceIds={}, durationMinutes={}, earliestStart={},"
                        + " horizonDays={}, limit={}, X-Correlation-Id={}",
                locationId,
                serviceIds,
                durationMinutes,
                earliestStart,
                horizonDays,
                limit,
                correlationId);
        OpeningSearchResponse response = openingSearchService.search(new OpeningSearchQuery(
                locationId, serviceIds, durationMinutes, earliestStart, vehicleId, technicianId, horizonDays, limit));
        return ResponseEntity.ok(response);
    }
}
