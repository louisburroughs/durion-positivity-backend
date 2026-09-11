package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.service.EventSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying aggregated event summaries by timeframe.
 * Returns event counts grouped by event type for the last hour, day, or week.
 *
 * <h2>Security Model</h2>
 * <p>
 * Uses the same shared-secret security filter ({@code EventsApiSecurityFilter})
 * as {@link EventTypeController} and {@link EmitEventController}. GET requests
 * are allowed without authentication per the existing security filter policy.
 * </p>
 *
 * <h2>Tenant scope (ADR-0062 plan WS6)</h2>
 * <p>
 * The counts carry a tenant dimension with global rollups. A caller bound to an ordinary tenant
 * (the gateway's {@code X-Tenant-Id}) reads its own tenant's counts and may not name another:
 * {@code tenantId} from such a caller is a 403. A caller bound to the platform tenant reads the
 * global rollup (the sum across tenants) by default, or one tenant when it names {@code tenantId}.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/events/summary")
@Tag(name = "Event Summary", description = "Query aggregated event counts by timeframe")
public class EventSummaryController {

    private static final String TENANT_ID_DESCRIPTION = "Tenant to report on; platform-tenant callers only. Omitted,"
            + " an ordinary tenant reads its own counts and the platform tenant reads the global rollup (the sum"
            + " across tenants). Named by a caller that is not the platform tenant, the request is refused with"
            + " 403.";

    private static final String TENANT_SCOPE_DESCRIPTION = """
            Counts carry a tenant dimension (ADR-0062): a caller bound to an ordinary tenant sees its own \
            tenant's counts; the platform tenant sees the global rollup summed across tenants, or one \
            tenant when it names tenantId. tenantId from any other caller is refused with 403.
            """;

    private final EventSummaryService eventSummaryService;

    @GetMapping("/lastHour")
    @EmitEvent(id = "EVENT_RECEIVER_SUMMARY_LAST_HOUR", apiVersion = "1")
    @Operation(
            operationId = "getEventSummaryLastHour",
            summary = "Get event summary for the last hour",
            description = """
                    Returns emitted-event counts grouped by event type code for the trailing 60 minutes, read from \
                    the emitted_event_hourly TimescaleDB continuous aggregate.
                    Use this tool for a near-real-time pulse of platform event traffic; use getEventSummaryLastDay or \
                    getEventSummaryLastWeek instead for longer trend windows.
                    Preconditions: none beyond service availability; GET requests bypass the shared-secret filter, \
                    and the aggregate refreshes hourly with a one-hour end offset, so the newest counts can lag by up \
                    to an hour.
                    Required inputs: none; the window is fixed at one hour and cannot be parameterized. \
                    """ + TENANT_SCOPE_DESCRIPTION + """
                    Emits an EVENT_RECEIVER_SUMMARY_LAST_HOUR event recording the query itself; the read changes no \
                    stored state.
                    Returns 200 with a list of event-type and count pairs, which is empty when no events fall inside \
                    the window, and 403 when tenantId is named by a caller that is not the platform tenant.
                    """,
            tags = {"Event Summary"})
    @ApiResponse(
            responseCode = "200",
            description = "Summary returned successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventSummaryResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "tenantId named by a caller that is not the platform tenant",
            content = @Content)
    public ResponseEntity<List<EventSummaryResponse>> getLastHourSummary(
            @Parameter(description = TENANT_ID_DESCRIPTION, example = "01900000-0000-7000-8000-000000000001")
                    @RequestParam(required = false)
                    UUID tenantId) {
        log.info("Fetching event summary for the last hour");
        return ResponseEntity.ok(eventSummaryService.getLastHourSummary(tenantId));
    }

    @GetMapping("/lastDay")
    @EmitEvent(id = "EVENT_RECEIVER_SUMMARY_LAST_DAY", apiVersion = "1")
    @Operation(
            operationId = "getEventSummaryLastDay",
            summary = "Get event summary for the last day",
            description = """
                    Returns emitted-event counts grouped by event type code for the trailing 24 hours, read from the \
                    emitted_event_hourly TimescaleDB continuous aggregate.
                    Use this tool for a daily view of platform event traffic; use getEventSummaryLastHour instead for \
                    a near-real-time pulse, or getEventSummaryLastWeek for the weekly trend.
                    Preconditions: none beyond service availability; GET requests bypass the shared-secret filter, \
                    and the aggregate refreshes hourly with a one-hour end offset, so the newest counts can lag by up \
                    to an hour.
                    Required inputs: none; the window is fixed at 24 hours and cannot be parameterized. \
                    """ + TENANT_SCOPE_DESCRIPTION + """
                    Emits an EVENT_RECEIVER_SUMMARY_LAST_DAY event recording the query itself; the read changes no \
                    stored state.
                    Returns 200 with a list of event-type and count pairs, which is empty when no events fall inside \
                    the window, and 403 when tenantId is named by a caller that is not the platform tenant.
                    """,
            tags = {"Event Summary"})
    @ApiResponse(
            responseCode = "200",
            description = "Summary returned successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventSummaryResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "tenantId named by a caller that is not the platform tenant",
            content = @Content)
    public ResponseEntity<List<EventSummaryResponse>> getLastDaySummary(
            @Parameter(description = TENANT_ID_DESCRIPTION, example = "01900000-0000-7000-8000-000000000001")
                    @RequestParam(required = false)
                    UUID tenantId) {
        log.info("Fetching event summary for the last day");
        return ResponseEntity.ok(eventSummaryService.getLastDaySummary(tenantId));
    }

    @GetMapping("/lastWeek")
    @EmitEvent(id = "EVENT_RECEIVER_SUMMARY_LAST_WEEK", apiVersion = "1")
    @Operation(
            operationId = "getEventSummaryLastWeek",
            summary = "Get event summary for the last week",
            description = """
                    Returns emitted-event counts grouped by event type code for the trailing 7 days, read from the \
                    emitted_event_hourly TimescaleDB continuous aggregate.
                    Use this tool for a weekly trend of platform event traffic; use getEventSummaryLastHour or \
                    getEventSummaryLastDay instead when a shorter window is wanted.
                    Preconditions: none beyond service availability; GET requests bypass the shared-secret filter, \
                    and the aggregate refreshes hourly with a one-hour end offset, so the newest counts can lag by up \
                    to an hour.
                    Required inputs: none; the window is fixed at 7 days and cannot be parameterized. \
                    """ + TENANT_SCOPE_DESCRIPTION + """
                    Emits an EVENT_RECEIVER_SUMMARY_LAST_WEEK event recording the query itself; the read changes no \
                    stored state.
                    Returns 200 with a list of event-type and count pairs, which is empty when no events fall inside \
                    the window, and 403 when tenantId is named by a caller that is not the platform tenant.
                    """,
            tags = {"Event Summary"})
    @ApiResponse(
            responseCode = "200",
            description = "Summary returned successfully",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventSummaryResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "tenantId named by a caller that is not the platform tenant",
            content = @Content)
    public ResponseEntity<List<EventSummaryResponse>> getLastWeekSummary(
            @Parameter(description = TENANT_ID_DESCRIPTION, example = "01900000-0000-7000-8000-000000000001")
                    @RequestParam(required = false)
                    UUID tenantId) {
        log.info("Fetching event summary for the last week");
        return ResponseEntity.ok(eventSummaryService.getLastWeekSummary(tenantId));
    }
}
