package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.ReopenedWorkorderAnalyticsResponse;
import com.positivity.workorder.internal.dto.TechnicianLaborAnalyticsResponse;
import com.positivity.workorder.internal.dto.WorkorderStatusTransitionsResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.WorkorderAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 2 cross-workorder analytics (E5 #1593, E6 #1594, E7 #1595): status-transition history,
 * reopened-workorder events, and per-technician labor summaries.
 *
 * <p>All three endpoints share the {@code workorder:analytics:view} permission (they are
 * read-only reporting surfaces over the same underlying data, aimed at the same shop-management
 * audience) and the same truncation contract: a capped result sets {@code truncated=true} in the
 * body rather than silently dropping rows, so a caller can tell a capped result from a complete
 * one without a second call.
 */
@Tag(
        name = "Workorder Analytics",
        description = "Cross-workorder reporting: status transitions, reopens, technician labor")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"workorder:analytics:view"})
@Validated
public class WorkorderAnalyticsController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_WITHIN_DAYS = 7;
    private static final int MAX_WITHIN_DAYS = 90;

    private final WorkorderAnalyticsService analyticsService;

    @GetMapping(value = "/status-transitions", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "WORKORDER_STATUS_TRANSITIONS_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getWorkorderStatusTransitions",
            summary = "Get Workorder Status Transitions",
            description = """
                    Returns work_order_state_transitions rows either for one workorder (woId) or for a date \
                    range (startDate/endDate, optionally narrowed by from/to status), ordered oldest first. \
                    Field mapping: at <- transitionedAt, actorId <- transitionedBy.
                    Use this tool for status-change history and as the backing projection for \
                    getReopenedWorkorderAnalytics; use getTechnicianLaborAnalytics instead for labor/revenue \
                    rollups, not raw transition rows.
                    Preconditions: exactly one calling mode — woId alone, or startDate+endDate (from/to \
                    optional) — never both, never neither; an unbounded scan is never performed.
                    Required inputs: either woId, or startDate and endDate (ISO dates, endDate on or after \
                    startDate).
                    Emits a WORKORDER_STATUS_TRANSITIONS_VIEW audit event; no state changes.
                    Returns 400 when the calling mode is ambiguous or incomplete, or endDate precedes \
                    startDate. Response truncated=true when more rows matched than limit allowed.
                    """,
            tags = {"Workorder Analytics"})
    @ApiResponse(
            responseCode = "200",
            description = "Status transitions returned successfully",
            content = @Content(schema = @Schema(implementation = WorkorderStatusTransitionsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Ambiguous/incomplete parameter combination, or endDate before startDate",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing workorder:analytics:view")
    public WorkorderStatusTransitionsResponse getWorkorderStatusTransitions(
            @Parameter(description = "Single workorder id — mutually exclusive with the range params below")
                    @RequestParam(required = false)
                    @Nullable
                    UUID woId,
            @Parameter(description = "Filter to transitions FROM this status (range mode only)")
                    @RequestParam(required = false)
                    @Nullable
                    WorkorderStatus from,
            @Parameter(description = "Filter to transitions TO this status (range mode only)")
                    @RequestParam(required = false)
                    @Nullable
                    WorkorderStatus to,
            @Parameter(description = "Range start date (YYYY-MM-DD), inclusive", example = "2026-06-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate startDate,
            @Parameter(description = "Range end date (YYYY-MM-DD), inclusive", example = "2026-06-30")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate endDate,
            @Parameter(description = "Maximum rows to return (default 100, max 500)")
                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
                    @Min(1)
                    @Max(MAX_LIMIT)
                    int limit) {
        return analyticsService.getStatusTransitions(woId, from, to, startDate, endDate, limit);
    }

    @GetMapping(value = "/analytics/reopened", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "WORKORDER_ANALYTICS_REOPENED_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getReopenedWorkorderAnalytics",
            summary = "Get Reopened Workorder Analytics",
            description = """
                    Returns one row per reopen event for work orders completed in the window and reopened \
                    within withinDays of that completion, per event rather than pre-counted, so a workorder \
                    reopened twice inside the window produces two separate rows for the caller to group and \
                    count by technician.
                    Use this tool for reopen and rework-quality questions; use getWorkorderStatusTransitions \
                    instead for the raw per-workorder transition history.
                    The technicianId is the actor recorded on the completing state transition, not the actor \
                    who reopened the work order, resolved to a stable person id via the active user-link \
                    replica; a row is excluded, never guessed, when that actor is null, blank, or cannot be \
                    resolved to a technician.
                    Rows are computed from the persisted work_order_state_transitions ledger rather than the \
                    workorder's current status or isReopened snapshot, so a work order completed, reopened, \
                    and completed again surfaces as distinct rows even though its current status never \
                    leaves COMPLETED.
                    Preconditions: none beyond a valid date range.
                    Required inputs: startDate and endDate, ISO dates with endDate on or after startDate, \
                    both anchored on the completion date rather than the reopen date; withinDays is \
                    optional, defaulting to 7 with a maximum of 90.
                    Emits a WORKORDER_ANALYTICS_REOPENED_VIEW audit event; no state changes.
                    Returns 400 when endDate precedes startDate or withinDays is out of range, and reports \
                    truncated=true in the response when more rows matched than the limit allowed.
                    """,
            tags = {"Workorder Analytics"})
    @ApiResponse(
            responseCode = "200",
            description = "Reopened-workorder analytics returned successfully",
            content = @Content(schema = @Schema(implementation = ReopenedWorkorderAnalyticsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid date range or withinDays out of bounds",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing workorder:analytics:view")
    public ReopenedWorkorderAnalyticsResponse getReopenedWorkorderAnalytics(
            @Parameter(
                            description = "Completion-date range start (YYYY-MM-DD), inclusive",
                            required = true,
                            example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @Parameter(
                            description = "Completion-date range end (YYYY-MM-DD), inclusive",
                            required = true,
                            example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate,
            @Parameter(description = "Reopen must occur within this many days of completion (default 7, max 90)")
                    @RequestParam(defaultValue = "" + DEFAULT_WITHIN_DAYS)
                    @Min(1)
                    @Max(MAX_WITHIN_DAYS)
                    int withinDays,
            @Parameter(description = "Maximum rows to return (default 100, max 500)")
                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
                    @Min(1)
                    @Max(MAX_LIMIT)
                    int limit) {
        return analyticsService.getReopenedWorkorders(startDate, endDate, withinDays, limit);
    }

    @GetMapping(value = "/analytics/technician-labor", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "WORKORDER_ANALYTICS_TECHNICIAN_LABOR_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getTechnicianLaborAnalytics",
            summary = "Get Technician Labor Analytics",
            description = """
                    Returns one row per technician summarizing completedWoCount, billedHours and \
                    laborRevenue for the window, with each column independently windowed so they can \
                    disagree at month boundaries: completedWoCount and laborRevenue anchor on the \
                    workorder's completion date, attributed to the technician on the completing state \
                    transition, while billedHours anchors on the labor entry's own log time and can fall in \
                    a different period than the workorder's eventual completion.
                    A technician can appear with billedHours logged but completedWoCount=0 on a workorder \
                    not yet completed, or the reverse, and laborRevenue sums ext_invoice.laborTotal across \
                    invoices for that technician's completed work orders, excluding rather than zeroing an \
                    invoice whose laborTotal is null because its source event carried no line detail.
                    Use this tool for labor productivity and revenue-per-technician questions; use \
                    getReopenedWorkorderAnalytics instead for rework and quality.
                    Preconditions: none beyond a valid date range.
                    Required inputs: startDate and endDate, ISO dates with endDate on or after startDate.
                    Emits a WORKORDER_ANALYTICS_TECHNICIAN_LABOR_VIEW audit event; no state changes.
                    Returns 400 when endDate precedes startDate, and reports truncated=true in the response, \
                    with rows ordered by billedHours descending, when more technicians matched than the \
                    limit allowed.
                    """,
            tags = {"Workorder Analytics"})
    @ApiResponse(
            responseCode = "200",
            description = "Technician labor analytics returned successfully",
            content = @Content(schema = @Schema(implementation = TechnicianLaborAnalyticsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid date range",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing workorder:analytics:view")
    public TechnicianLaborAnalyticsResponse getTechnicianLaborAnalytics(
            @Parameter(
                            description = "Window start date (YYYY-MM-DD), inclusive",
                            required = true,
                            example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @Parameter(description = "Window end date (YYYY-MM-DD), inclusive", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate,
            @Parameter(description = "Maximum rows to return (default 100, max 500)")
                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
                    @Min(1)
                    @Max(MAX_LIMIT)
                    int limit) {
        return analyticsService.getTechnicianLabor(startDate, endDate, limit);
    }
}
