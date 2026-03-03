package com.positivity.workorder.internal.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.WorkexecJobTimeTotalResponse;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedRequest;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerEntryResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerStartRequest;
import com.positivity.workorder.internal.dto.WorkexecTimerStopResponse;
import com.positivity.workorder.service.WorkexecTimeTrackingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/v1/workexec")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Workexec Time Tracking API", description = "Endpoints for timer operations, labor entry creation, and job time totals")
public class WorkexecTimeTrackingController {

    private static final String ERROR_CODE_KEY = "code";
    private static final String ERROR_MESSAGE_KEY = "message";
    private static final String ERROR_INVALID_REQUEST = "WORKEXEC_INVALID_REQUEST";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ID_REQUIRED_MESSAGE = "Valid X-User-Id header is required";

    private final WorkexecTimeTrackingService service;

    @GetMapping("/job-time-totals")
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    @Operation(summary = "Get job time totals", description = "Retrieve aggregated tracked hours for a date range, timezone, and optional location/technicians")
    @ApiResponse(responseCode = "200", description = "Job time totals returned successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<Object> getJobTimeTotals(
            @Parameter(description = "Start date (inclusive)", example = "2026-03-01") @RequestParam("startDate") LocalDate startDate,
            @Parameter(description = "End date (inclusive)", example = "2026-03-02") @RequestParam("endDate") LocalDate endDate,
            @Parameter(description = "IANA timezone", example = "America/New_York") @RequestParam("timezone") String timezone,
            @Parameter(description = "Optional location filter", example = "550e8400-e29b-41d4-a716-446655440010") @RequestParam(value = "locationId", required = false) UUID locationId,
            @Parameter(description = "Optional technician filters", example = "[\"550e8400-e29b-41d4-a716-446655440120\"]") @RequestParam(value = "technicianIds", required = false) List<UUID> technicianIds) {

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ex) {
            return badRequest(ERROR_INVALID_REQUEST, "Invalid timezone value");
        }

        if (endDate.isBefore(startDate)) {
            return badRequest(ERROR_INVALID_REQUEST, "endDate must be on or after startDate");
        }

        List<WorkexecJobTimeTotalResponse> response = service.getJobTimeTotals(
                startDate,
                endDate,
                zoneId,
                locationId,
                technicianIds == null ? Collections.emptyList() : technicianIds);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/labor-performed")
    @EmitEvent(id = "WORKEXEC_LABOR_PERFORMED_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Create labor performed entry", description = "Create a labor-performed record with idempotency support")
    @ApiResponse(responseCode = "201", description = "Labor entry created successfully", content = @Content(schema = @Schema(implementation = WorkexecLaborPerformedResponse.class)))
    @ApiResponse(responseCode = "200", description = "Idempotent replay returned existing labor entry", content = @Content(schema = @Schema(implementation = WorkexecLaborPerformedResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Related resource not found")
    @ApiResponse(responseCode = "409", description = "Conflict while recording labor")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Labor-performed payload", required = true, content = @Content(schema = @Schema(implementation = WorkexecLaborPerformedRequest.class), examples = @ExampleObject(value = "{\"workorderId\":\"550e8400-e29b-41d4-a716-446655440001\"}")))
    public ResponseEntity<Object> createLaborPerformed(
            @Valid @RequestBody WorkexecLaborPerformedRequest request,
            @Parameter(description = "Idempotency key for safe retries", example = "workexec-labor-550e8400-e29b-41d4-a716-446655440001") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Parameter(description = "Optional correlation id", example = "corr-12345") @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return badRequest(ERROR_INVALID_REQUEST, "Idempotency-Key header is required");
        }

        try {
            WorkexecTimeTrackingService.LaborPerformedResult result = service.recordLaborPerformed(request,
                    idempotencyKey);
            WorkexecLaborPerformedResponse response = result.response();

            HttpHeaders headers = new HttpHeaders();
            if (correlationId != null && !correlationId.isBlank()) {
                headers.add("X-Correlation-Id", correlationId);
            }

            if (result.replayed()) {
                headers.add("Idempotency-Replayed", "true");
                return new ResponseEntity<>(response, headers, HttpStatus.OK);
            }
            return new ResponseEntity<>(response, headers, HttpStatus.CREATED);
        } catch (NoSuchElementException ex) {
            return notFound("NOT_FOUND", ex.getMessage());
        } catch (WorkexecTimeTrackingService.WorkexecConflictException ex) {
            return conflict(ex.getCode(), ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return badRequest(ERROR_INVALID_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/time-entries/timer/active")
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    @Operation(summary = "Get active timers", description = "Retrieve active timer entries for the authenticated mechanic")
    @ApiResponse(responseCode = "200", description = "Active timers returned successfully", content = @Content(schema = @Schema(implementation = WorkexecTimerEntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid X-User-Id header")
    public ResponseEntity<Object> getActiveTimerEntries(
            @Parameter(description = "Mechanic user id", example = "550e8400-e29b-41d4-a716-446655440100") @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {

        UUID mechanicId = parseRequiredUuidHeader(userIdHeader, USER_ID_HEADER);
        if (mechanicId == null) {
            return badRequest(ERROR_INVALID_REQUEST, USER_ID_REQUIRED_MESSAGE);
        }

        List<WorkexecTimerEntryResponse> active = service.getActiveTimers(mechanicId);
        return ResponseEntity.ok(active);
    }

    @PostMapping("/time-entries/timer/start")
    @EmitEvent(id = "WORKEXEC_TIMER_START", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Start timer", description = "Start a workexec timer entry for the authenticated mechanic")
    @ApiResponse(responseCode = "201", description = "Timer started successfully", content = @Content(schema = @Schema(implementation = WorkexecTimerEntryResponse.class)))
    @ApiResponse(responseCode = "200", description = "Idempotent replay returned existing timer", content = @Content(schema = @Schema(implementation = WorkexecTimerEntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid X-User-Id header")
    @ApiResponse(responseCode = "404", description = "Referenced resource not found")
    @ApiResponse(responseCode = "409", description = "Conflict while starting timer")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Timer start payload", required = true, content = @Content(schema = @Schema(implementation = WorkexecTimerStartRequest.class)))
    public ResponseEntity<Object> startTimer(
            @Parameter(description = "Mechanic user id", example = "550e8400-e29b-41d4-a716-446655440100") @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader,
            @Parameter(description = "Optional idempotency key", example = "timer-start-550e8400-e29b-41d4-a716-446655440001") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkexecTimerStartRequest request) {

        UUID mechanicId = parseRequiredUuidHeader(userIdHeader, USER_ID_HEADER);
        if (mechanicId == null) {
            return badRequest(ERROR_INVALID_REQUEST, USER_ID_REQUIRED_MESSAGE);
        }

        try {
            WorkexecTimeTrackingService.TimerStartResult result = service.startTimer(mechanicId, request,
                    idempotencyKey);
            HttpHeaders headers = new HttpHeaders();
            if (result.replayed()) {
                headers.add("Idempotency-Replayed", "true");
                return new ResponseEntity<>(result.response(), headers, HttpStatus.OK);
            }
            return new ResponseEntity<>(result.response(), headers, HttpStatus.CREATED);
        } catch (NoSuchElementException ex) {
            return notFound("NOT_FOUND", ex.getMessage());
        } catch (WorkexecTimeTrackingService.WorkexecConflictException ex) {
            return conflict(ex.getCode(), ex.getMessage());
        }
    }

    @PostMapping("/time-entries/timer/stop")
    @EmitEvent(id = "WORKEXEC_TIMER_STOP", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Stop timers", description = "Stop active timer entries for the authenticated mechanic")
    @ApiResponse(responseCode = "200", description = "Timers stopped successfully", content = @Content(schema = @Schema(implementation = WorkexecTimerStopResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid X-User-Id header")
    @ApiResponse(responseCode = "409", description = "Conflict while stopping timers")
    public ResponseEntity<Object> stopTimers(
            @Parameter(description = "Mechanic user id", example = "550e8400-e29b-41d4-a716-446655440100") @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {

        UUID mechanicId = parseRequiredUuidHeader(userIdHeader, USER_ID_HEADER);
        if (mechanicId == null) {
            return badRequest(ERROR_INVALID_REQUEST, USER_ID_REQUIRED_MESSAGE);
        }

        try {
            List<WorkexecTimerEntryResponse> stopped = service.stopTimers(mechanicId);
            return ResponseEntity.ok(WorkexecTimerStopResponse.builder().stopped(stopped).build());
        } catch (WorkexecTimeTrackingService.WorkexecConflictException ex) {
            return conflict(ex.getCode(), ex.getMessage());
        }
    }

    private UUID parseRequiredUuidHeader(String value, String headerName) {
        if (value == null || value.isBlank()) {
            log.warn("Missing required header {}", headerName);
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid UUID for header {}: {}", headerName, value);
            return null;
        }
    }

    private ResponseEntity<Object> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of(ERROR_CODE_KEY, code, ERROR_MESSAGE_KEY, message));
    }

    private ResponseEntity<Object> conflict(String code, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(ERROR_CODE_KEY, code, ERROR_MESSAGE_KEY, message));
    }

    private ResponseEntity<Object> notFound(String code, String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(ERROR_CODE_KEY, code, ERROR_MESSAGE_KEY, message));
    }
}
