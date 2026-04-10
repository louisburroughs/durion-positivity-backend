package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.services.EventSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
  @Operation(summary = "Get event summary for the last hour", description = "Returns event counts grouped by event type for the last 60 minutes")
  @ApiResponse(responseCode = "200", description = "Summary returned successfully", content = @Content(schema = @Schema(implementation = EventSummaryResponse.class)))
  public ResponseEntity<List<EventSummaryResponse>> getLastHourSummary() {
    log.info("Fetching event summary for the last hour");
    return ResponseEntity.ok(eventSummaryService.getLastHourSummary());
  }

  @GetMapping("/lastDay")
  @EmitEvent(id = "EVENT_RECEIVER_SUMMARY_LAST_DAY", apiVersion = "1")
  @Operation(summary = "Get event summary for the last day", description = "Returns event counts grouped by event type for the last 24 hours")
  @ApiResponse(responseCode = "200", description = "Summary returned successfully", content = @Content(schema = @Schema(implementation = EventSummaryResponse.class)))
  public ResponseEntity<List<EventSummaryResponse>> getLastDaySummary() {
    log.info("Fetching event summary for the last day");
    return ResponseEntity.ok(eventSummaryService.getLastDaySummary());
  }

  @GetMapping("/lastWeek")
  @EmitEvent(id = "EVENT_RECEIVER_SUMMARY_LAST_WEEK", apiVersion = "1")
  @Operation(summary = "Get event summary for the last week", description = "Returns event counts grouped by event type for the last 7 days")
  @ApiResponse(responseCode = "200", description = "Summary returned successfully", content = @Content(schema = @Schema(implementation = EventSummaryResponse.class)))
  public ResponseEntity<List<EventSummaryResponse>> getLastWeekSummary() {
    log.info("Fetching event summary for the last week");
    return ResponseEntity.ok(eventSummaryService.getLastWeekSummary());
  }
}
