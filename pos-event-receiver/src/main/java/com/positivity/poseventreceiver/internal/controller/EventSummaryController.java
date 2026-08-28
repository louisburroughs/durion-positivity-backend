package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.service.EventSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/events/summary")
@Tag(name = "Event Summary", description = "Query aggregated event counts by timeframe")
public class EventSummaryController {

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
                    Required inputs: none; the window is fixed at one hour and cannot be parameterized.
                    Emits an EVENT_RECEIVER_SUMMARY_LAST_HOUR event recording the query itself; the read changes no \
                    stored state.
                    Returns 200 with a list of event-type and count pairs, which is empty when no events fall inside \
                    the window.
                    """,
            tags = {"Event Summary"})
    @ApiResponse(
            responseCode = "200",
            description = "Summary returned successfully",
            content = @Content(schema = @Schema(implementation = EventSummaryResponse.class)))
    public ResponseEntity<List<EventSummaryResponse>> getLastHourSummary() {
        log.info("Fetching event summary for the last hour");
        return ResponseEntity.ok(eventSummaryService.getLastHourSummary());
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
                    Required inputs: none; the window is fixed at 24 hours and cannot be parameterized.
                    Emits an EVENT_RECEIVER_SUMMARY_LAST_DAY event recording the query itself; the read changes no \
                    stored state.
                    Returns 200 with a list of event-type and count pairs, which is empty when no events fall inside \
                    the window.
                    """,
            tags = {"Event Summary"})
    @ApiResponse(
            responseCode = "200",
            description = "Summary returned successfully",
            content = @Content(schema = @Schema(implementation = EventSummaryResponse.class)))
    public ResponseEntity<List<EventSummaryResponse>> getLastDaySummary() {
        log.info("Fetching event summary for the last day");
        return ResponseEntity.ok(eventSummaryService.getLastDaySummary());
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
                    Required inputs: none; the window is fixed at 7 days and cannot be parameterized.
                    Emits an EVENT_RECEIVER_SUMMARY_LAST_WEEK event recording the query itself; the read changes no \
                    stored state.
                    Returns 200 with a list of event-type and count pairs, which is empty when no events fall inside \
                    the window.
                    """,
            tags = {"Event Summary"})
    @ApiResponse(
            responseCode = "200",
            description = "Summary returned successfully",
            content = @Content(schema = @Schema(implementation = EventSummaryResponse.class)))
    public ResponseEntity<List<EventSummaryResponse>> getLastWeekSummary() {
        log.info("Fetching event summary for the last week");
        return ResponseEntity.ok(eventSummaryService.getLastWeekSummary());
    }
}
