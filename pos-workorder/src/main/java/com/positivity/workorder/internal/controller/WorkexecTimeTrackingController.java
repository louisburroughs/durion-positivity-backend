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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/v1/workexec")
@RequiredArgsConstructor
@Slf4j
public class WorkexecTimeTrackingController {

    private static final String ERROR_CODE_KEY = "code";
    private static final String ERROR_MESSAGE_KEY = "message";
    private static final String ERROR_INVALID_REQUEST = "WORKEXEC_INVALID_REQUEST";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ID_REQUIRED_MESSAGE = "Valid X-User-Id header is required";

    private final WorkexecTimeTrackingService service;

    @GetMapping("/job-time-totals")
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    public ResponseEntity<Object> getJobTimeTotals(
            @RequestParam("startDate") LocalDate startDate,
            @RequestParam("endDate") LocalDate endDate,
            @RequestParam("timezone") String timezone,
            @RequestParam(value = "locationId", required = false) UUID locationId,
            @RequestParam(value = "technicianIds", required = false) List<UUID> technicianIds) {

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
    public ResponseEntity<Object> createLaborPerformed(
            @Valid @RequestBody WorkexecLaborPerformedRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

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
    public ResponseEntity<Object> getActiveTimerEntries(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {

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
    public ResponseEntity<Object> startTimer(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
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
    public ResponseEntity<Object> stopTimers(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {

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
