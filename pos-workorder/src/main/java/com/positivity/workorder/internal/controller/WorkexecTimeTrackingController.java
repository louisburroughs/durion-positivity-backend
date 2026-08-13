package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@RestController
@RequestMapping("/v1/workexec")
@RequiredArgsConstructor
@Slf4j
@Tag(
        name = "Workexec Time Tracking API",
        description = "Endpoints for timer operations, labor entry creation, and job time totals")
public class WorkexecTimeTrackingController {

    private static final String ERROR_CODE_KEY = "code";
    private static final String ERROR_MESSAGE_KEY = "message";
    private static final String ERROR_INVALID_REQUEST = "WORKEXEC_INVALID_REQUEST";
    private static final String USER_ID_REQUIRED_MESSAGE = "Authenticated user id must be a valid UUID";

    private final WorkexecTimeTrackingService service;

    @GetMapping("/job-time-totals")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:view"})
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    @Operation(operationId = "getJobTimeTotals", summary = "Get Aggregated Job Time Totals", description = """
                    Returns tracked job minutes aggregated per technician, location, and local calendar day over \
                    an inclusive date range interpreted in the supplied timezone.
                    Use this tool for payroll or utilization reporting across days; do not use getLaborHistory, \
                    which lists individual labor entries for one workorder.
                    Preconditions: none beyond the caller holding workorder:labor:view; totals derive from \
                    recorded labor entries.
                    Required inputs: startDate and endDate (ISO dates, endDate on or after startDate) and \
                    timezone (IANA name); locationId and technicianIds are optional filters.
                    No events are emitted and no state changes; this is a read-only aggregation.
                    Returns 400 when the timezone is invalid or endDate precedes startDate, and 200 with an \
                    empty list when no time was tracked in the range.
                    """)
    @ApiResponse(responseCode = "200", description = "Job time totals returned successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<Object> getJobTimeTotals(
            @Parameter(description = "Start date (inclusive)", example = "2026-03-01") @RequestParam("startDate")
                    LocalDate startDate,
            @Parameter(description = "End date (inclusive)", example = "2026-03-02") @RequestParam("endDate")
                    LocalDate endDate,
            @Parameter(description = "IANA timezone", example = "America/New_York") @RequestParam("timezone")
                    String timezone,
            @Parameter(description = "Optional location filter", example = "550e8400-e29b-41d4-a716-446655440010")
                    @RequestParam(value = "locationId", required = false)
                    UUID locationId,
            @Parameter(
                            description = "Optional technician filters",
                            example = "[\"550e8400-e29b-41d4-a716-446655440120\"]")
                    @RequestParam(value = "technicianIds", required = false)
                    List<UUID> technicianIds) {

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception _) {
            return badRequest(ERROR_INVALID_REQUEST, "Invalid timezone value");
        }

        if (endDate.isBefore(startDate)) {
            return badRequest(ERROR_INVALID_REQUEST, "endDate must be on or after startDate");
        }

        List<WorkexecJobTimeTotalResponse> response = service
                .getJobTimeTotals(
                        startDate,
                        endDate,
                        zoneId,
                        locationId,
                        technicianIds == null ? Collections.emptyList() : technicianIds)
                .stream()
                .map(this::toJobTimeTotalResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/labor-performed")
    @EmitEvent(id = "WORKEXEC_LABOR_PERFORMED_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:add"})
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(operationId = "createLaborPerformed", summary = "Record Labor Performed Quantity", description = """
                    Records a completed quantity of labor hours against a workorder as a closed labor entry, \
                    back-dating the start time from performedAt by the reported quantity.
                    Use this tool when labor arrives as a finished quantity from another system; do not use \
                    startTimer and stopTimers, which capture live elapsed time instead of reported hours.
                    Preconditions: the workorder must exist and not be in a state blocked for labor posting, \
                    such as CANCELLED.
                    Required inputs: workorderId, technicianId, performedAt (ISO instant), labor.quantity \
                    (positive decimal), labor.unit (must be HOURS), source.system, and source.sourceReferenceId; \
                    the Idempotency-Key header is mandatory and a repeated key replays the original entry with \
                    200 and an Idempotency-Replayed header.
                    Emits a WORKEXEC_LABOR_PERFORMED_CREATE event.
                    Returns 201 on creation (200 on replay), 404 when the workorder cannot be found, 409 with \
                    code WORKEXEC_CONFLICT_WORKORDER_STATE when the workorder state blocks posting, and 400 when \
                    the Idempotency-Key is missing, the unit is not HOURS, or the quantity is not positive.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Labor entry created successfully",
            content = @Content(schema = @Schema(implementation = WorkexecLaborPerformedResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Idempotent replay returned existing labor entry",
            content = @Content(schema = @Schema(implementation = WorkexecLaborPerformedResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Related resource not found")
    @ApiResponse(responseCode = "409", description = "Conflict while recording labor")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Completed labor quantity with its source-system provenance.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = WorkexecLaborPerformedRequest.class),
                            examples = @ExampleObject(name = "Reported hours", value = """
                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a81",
                                                     "technicianId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a82",
                                                     "performedAt":"2026-08-13T16:30:00Z",
                                                     "labor":{"quantity":1.5,"unit":"HOURS"},
                                                     "source":{"system":"MOBILE_APP","sourceReferenceId":"job-4711"}}
                                                    """)))
    public ResponseEntity<Object> createLaborPerformed(
            @Valid @RequestBody WorkexecLaborPerformedRequest request,
            @Parameter(
                            description = "Idempotency key for safe retries",
                            example = "workexec-labor-550e8400-e29b-41d4-a716-446655440001")
                    @RequestHeader("Idempotency-Key")
                    String idempotencyKey,
            @Parameter(description = "Optional correlation id", example = "corr-12345")
                    @RequestHeader(value = "X-Correlation-Id", required = false)
                    String correlationId) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return badRequest(ERROR_INVALID_REQUEST, "Idempotency-Key header is required");
        }

        try {
            WorkexecTimeTrackingService.LaborPerformedResult result =
                    service.recordLaborPerformed(toLaborPerformedRequest(request), idempotencyKey);
            WorkexecLaborPerformedResponse response = toLaborPerformedResponse(result.response());

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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:view"})
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    @Operation(operationId = "getActiveTimers", summary = "Get Authenticated Mechanic Active Timers", description = """
                    Returns the authenticated mechanic's currently running timer entries, each with its \
                    workorder, workorder item, labor code, and start time.
                    Use this tool to check whether a timer is already running before startTimer; do not use \
                    getJobTimeTotals, which aggregates completed time rather than live timers.
                    Preconditions: the authenticated user id in the security context must be a UUID — the \
                    mechanic is always the caller, never a parameter.
                    Required inputs: none; identity comes entirely from the security context.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when the authenticated user id is missing or not a UUID, and 200 with an empty \
                    list when no timer is running.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Active timers returned successfully",
            content = @Content(schema = @Schema(implementation = WorkexecTimerEntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid authenticated user id")
    public ResponseEntity<Object> getActiveTimerEntries() {

        UUID mechanicId = resolveAuthenticatedMechanicId();
        if (mechanicId == null) {
            return badRequest(ERROR_INVALID_REQUEST, USER_ID_REQUIRED_MESSAGE);
        }

        List<WorkexecTimerEntryResponse> active = service.getActiveTimers(mechanicId).stream()
                .map(this::toTimerEntryResponse)
                .toList();
        return ResponseEntity.ok(active);
    }

    @PostMapping("/time-entries/timer/start")
    @EmitEvent(id = "WORKEXEC_TIMER_START", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:add", "workorder:labor:add_on_behalf"})
    @PreAuthorize("hasAnyAuthority('workorder:labor:add','workorder:labor:add_on_behalf')")
    @Operation(operationId = "startTimer", summary = "Start Workexec Labor Timer", description = """
                    Starts a live labor timer on a workorder, attributing the tracked time to the requested \
                    technician, the current assignment, or the caller, and creating a service line when the \
                    workorder has none.
                    Use this tool for live time capture; do not use createLaborPerformed, which records an \
                    already-completed quantity of hours.
                    Preconditions: the workorder must be in APPROVED, ASSIGNED, WORK_IN_PROGRESS, \
                    AWAITING_PARTS, or AWAITING_APPROVAL status, and the tracked technician must have no timer \
                    already running; attributing to someone who is neither the caller nor the current assignee \
                    requires workorder:labor:add_on_behalf plus a reason.
                    Required inputs: workorderId (UUID); workorderItemId, laborCode, technicianId, and reason \
                    are optional, and an Idempotency-Key header replays the original timer with 200.
                    Emits a WORKEXEC_TIMER_START event.
                    Returns 201 on start (200 on replay), 404 when the workorder or workorder item is missing, \
                    409 with TIMER_ALREADY_ACTIVE or INVALID_STATE on conflicts, 403 for unauthorized on-behalf \
                    attribution, and 400 when the caller's user id is not a UUID or an on-behalf reason is \
                    missing.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Timer started successfully",
            content = @Content(schema = @Schema(implementation = WorkexecTimerEntryResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Idempotent replay returned existing timer",
            content = @Content(schema = @Schema(implementation = WorkexecTimerEntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid authenticated user id, or missing reason")
    @ApiResponse(
            responseCode = "403",
            description = "Attributing labor to another technician without workorder:labor:add_on_behalf")
    @ApiResponse(responseCode = "404", description = "Referenced resource not found")
    @ApiResponse(responseCode = "409", description = "Conflict while starting timer")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Workorder to time against, with optional item, labor code, and attribution target.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = WorkexecTimerStartRequest.class),
                            examples = @ExampleObject(name = "Start on workorder", value = """
                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a81",
                                                     "workorderItemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a83",
                                                     "laborCode":"DIAG"}
                                                    """)))
    public ResponseEntity<Object> startTimer(
            @Parameter(
                            description = "Optional idempotency key",
                            example = "timer-start-550e8400-e29b-41d4-a716-446655440001")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody WorkexecTimerStartRequest request) {

        UUID mechanicId = resolveAuthenticatedMechanicId();
        if (mechanicId == null) {
            return badRequest(ERROR_INVALID_REQUEST, USER_ID_REQUIRED_MESSAGE);
        }

        try {
            WorkexecTimeTrackingService.TimerStartResult result =
                    service.startTimer(mechanicId, toTimerStartRequest(request), idempotencyKey);
            HttpHeaders headers = new HttpHeaders();
            if (result.replayed()) {
                headers.add("Idempotency-Replayed", "true");
                return new ResponseEntity<>(toTimerEntryResponse(result.response()), headers, HttpStatus.OK);
            }
            return new ResponseEntity<>(toTimerEntryResponse(result.response()), headers, HttpStatus.CREATED);
        } catch (NoSuchElementException ex) {
            return notFound("NOT_FOUND", ex.getMessage());
        } catch (WorkexecTimeTrackingService.WorkexecConflictException ex) {
            return conflict(ex.getCode(), ex.getMessage());
        }
    }

    @PostMapping("/time-entries/timer/stop")
    @EmitEvent(id = "WORKEXEC_TIMER_STOP", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:add"})
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(operationId = "stopTimers", summary = "Stop Authenticated Mechanic Timers", description = """
                    Stops every active timer the authenticated mechanic is tracked on or initiated, stamping the \
                    stop time and returning the closed entries with their durations.
                    Use this tool when a technician finishes timed work; do not use adjustLaborHours, which \
                    corrects hours on an entry after the fact.
                    Preconditions: at least one active timer must exist for the caller, either as the tracked \
                    technician or as the initiator of an on-behalf timer.
                    Required inputs: none — there is no request body, and identity comes from the security \
                    context.
                    Emits a WORKEXEC_TIMER_STOP event.
                    Returns 200 with the stopped entries, 409 with code NO_ACTIVE_TIMER when nothing is running, \
                    and 400 when the authenticated user id is missing or not a UUID.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Timers stopped successfully",
            content = @Content(schema = @Schema(implementation = WorkexecTimerStopResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid authenticated user id")
    @ApiResponse(responseCode = "409", description = "Conflict while stopping timers")
    public ResponseEntity<Object> stopTimers() {

        UUID mechanicId = resolveAuthenticatedMechanicId();
        if (mechanicId == null) {
            return badRequest(ERROR_INVALID_REQUEST, USER_ID_REQUIRED_MESSAGE);
        }

        try {
            List<WorkexecTimerEntryResponse> stopped = service.stopTimers(mechanicId).stream()
                    .map(this::toTimerEntryResponse)
                    .toList();
            return ResponseEntity.ok(
                    WorkexecTimerStopResponse.builder().stopped(stopped).build());
        } catch (WorkexecTimeTrackingService.WorkexecConflictException ex) {
            return conflict(ex.getCode(), ex.getMessage());
        }
    }

    private UUID resolveAuthenticatedMechanicId() {
        UUID mechanicId = SecurityContextHelper.getCurrentUserIdAsUuid().orElse(null);
        if (mechanicId == null) {
            log.warn("Missing authenticated UUID user id in security context");
        }
        return mechanicId;
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

    private WorkexecJobTimeTotalResponse toJobTimeTotalResponse(WorkexecTimeTrackingService.JobTimeTotal response) {
        return WorkexecJobTimeTotalResponse.builder()
                .technicianId(response.technicianId())
                .locationId(response.locationId())
                .localDate(response.localDate())
                .totalJobMinutes(response.totalJobMinutes())
                .build();
    }

    private WorkexecTimeTrackingService.LaborPerformedRequest toLaborPerformedRequest(
            WorkexecLaborPerformedRequest request) {
        return new WorkexecTimeTrackingService.LaborPerformedRequest(
                request.getWorkorderId(),
                request.getTechnicianId(),
                request.getPerformedAt(),
                new WorkexecTimeTrackingService.LaborQuantity(
                        request.getLabor().getQuantity(), request.getLabor().getUnit()),
                new WorkexecTimeTrackingService.SourceReference(
                        request.getSource().getSystem(), request.getSource().getSourceReferenceId()));
    }

    private WorkexecLaborPerformedResponse toLaborPerformedResponse(
            WorkexecTimeTrackingService.LaborPerformedResponse response) {
        return WorkexecLaborPerformedResponse.builder()
                .laborPerformedId(response.laborPerformedId())
                .workorderId(response.workorderId())
                .technicianId(response.technicianId())
                .performedAt(response.performedAt())
                .quantity(response.quantity())
                .unit(response.unit())
                .sourceSystem(response.sourceSystem())
                .sourceReferenceId(response.sourceReferenceId())
                .build();
    }

    private WorkexecTimeTrackingService.TimerStartRequest toTimerStartRequest(WorkexecTimerStartRequest request) {
        return new WorkexecTimeTrackingService.TimerStartRequest(
                request.getWorkorderId(),
                request.getWorkorderItemId(),
                request.getLaborCode(),
                request.getTechnicianId(),
                request.getReason());
    }

    private WorkexecTimerEntryResponse toTimerEntryResponse(WorkexecTimeTrackingService.TimerEntry response) {
        return WorkexecTimerEntryResponse.builder()
                .timeEntryId(response.timeEntryId())
                .mechanicId(response.mechanicId())
                .workorderId(response.workorderId())
                .workorderItemId(response.workorderItemId())
                .laborCode(response.laborCode())
                .startTime(response.startTime())
                .endTime(response.endTime())
                .durationInSeconds(response.durationInSeconds())
                .status(response.status())
                .build();
    }
}
