package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.AddBreakSegmentRequest;
import com.positivity.workorder.internal.dto.BreakSegmentResponse;
import com.positivity.workorder.internal.dto.StartWorkSessionRequest;
import com.positivity.workorder.internal.dto.StopWorkSessionRequest;
import com.positivity.workorder.internal.dto.WorkSessionResponse;
import com.positivity.workorder.service.WorkSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Work Session API", description = "Endpoints for managing technician work sessions and break segments")
@RestController
@RequestMapping("/v1/workorders/workSessions")
@RequiredArgsConstructor
public class WorkSessionController {

    private final WorkSessionService workSessionService;

    @Operation(operationId = "startWorkSession", summary = "Start a Technician Work Session", description = """
                    Creates an IN_PROGRESS work session binding a mechanic to a workorder task, stamping the \
                    start time from the server clock.
                    Use this tool when a technician clocks onto a task; do not use stopWorkSession, which ends a \
                    running session, or addBreakSegment, which pauses one.
                    Preconditions: the workorder must exist, and the mechanic must have no other IN_PROGRESS \
                    session unless overlapping sessions are enabled by configuration, the caller holds \
                    timekeeping:overlap_override, and an overlapOverrideReason is supplied.
                    Required inputs: mechanicId, workOrderId, workOrderTaskId, and locationId (all UUIDs); \
                    resourceId and overlapOverrideReason are optional.
                    Emits a WORKORDER_WORK_SESSION_START event; any overlap override is recorded with the \
                    overriding user and timestamp.
                    Returns 201 with the new session, 404 when the workorder does not exist, and 409 when the \
                    mechanic already has an active session and no valid override applies.
                    """)
    @ApiResponse(responseCode = "201", description = "Work session started successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request - missing required fields or work order not found")
    @PostMapping("/start")
    @EmitEvent(id = "WORKORDER_WORK_SESSION_START", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"timekeeping:work_session:create"})
    @PreAuthorize("hasAuthority('timekeeping:work_session:create')")
    public ResponseEntity<WorkSessionResponse> startWorkSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Session start details binding a mechanic to a workorder task and location.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Clock onto task",
                                                            value = """
                                                                    {"mechanicId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a51",
                                                                     "workOrderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a52",
                                                                     "workOrderTaskId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a53",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a54"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    StartWorkSessionRequest request) {
        WorkSessionResponse response = workSessionService.startSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "stopWorkSession", summary = "Stop an Active Work Session", description = """
                    Stops an IN_PROGRESS work session, transitioning it to COMPLETED and computing net worked \
                    seconds as elapsed time minus finished break segments.
                    Use this tool when a technician clocks off a task; do not use stopBreakSegment, which only \
                    ends a break while the session keeps running.
                    Preconditions: the session must exist, be IN_PROGRESS, and not be locked by payroll \
                    processing.
                    Required inputs: workSessionId (UUID) as a path parameter; the body's mechanicId is optional \
                    and not used to determine the session.
                    Emits a WORKORDER_WORK_SESSION_STOP event carrying the net duration.
                    Returns 404 when no session exists for the id, and 409 when the session is not IN_PROGRESS \
                    or is locked.
                    """)
    @ApiResponse(responseCode = "200", description = "Work session stopped successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - work session not active or validation failed")
    @ApiResponse(responseCode = "404", description = "Work session not found")
    @PostMapping("/{workSessionId}/stop")
    @EmitEvent(id = "WORKORDER_WORK_SESSION_STOP", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"timekeeping:work_session:stop"})
    @PreAuthorize("hasAuthority('timekeeping:work_session:stop')")
    public ResponseEntity<WorkSessionResponse> stopWorkSession(
            @Parameter(description = "ID of the work session to stop", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workSessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Stop confirmation payload; the session is identified by the path id.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Clock off",
                                                            value = """
                                                                    {"mechanicId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a51"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    StopWorkSessionRequest request) {
        WorkSessionResponse response = workSessionService.stopSession(workSessionId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "startBreakSegment", summary = "Start a Break Segment", description = """
                    Opens a break segment inside an active work session, recording the break start time so break \
                    minutes are excluded from the session's net duration.
                    Use this tool when a technician pauses work; do not use stopWorkSession, which ends the whole \
                    session rather than pausing it.
                    Preconditions: the session must exist, be IN_PROGRESS, not be locked, and have no other break \
                    segment still open.
                    Required inputs: workSessionId (UUID) as a path parameter; breakType (MEAL, REST, or OTHER) \
                    and notes are optional body fields.
                    Emits a WORKORDER_WORK_SESSION_BREAK_START event.
                    Returns 201 with the new break segment, 404 when the session does not exist, and 409 when \
                    the session is not IN_PROGRESS, is locked, or already has an open break.
                    """)
    @ApiResponse(responseCode = "201", description = "Break segment started successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - work session not active or validation failed")
    @ApiResponse(responseCode = "404", description = "Work session not found")
    @PostMapping("/{workSessionId}/breaks")
    @EmitEvent(id = "WORKORDER_WORK_SESSION_BREAK_START", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"timekeeping:work_session:break_start"})
    @PreAuthorize("hasAuthority('timekeeping:work_session:break_start')")
    public ResponseEntity<BreakSegmentResponse> addBreakSegment(
            @Parameter(description = "ID of the work session", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workSessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Break details classifying the pause within the running session.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Lunch break",
                                                            value = """
                                                                    {"breakType":"MEAL","notes":"Lunch"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AddBreakSegmentRequest request) {
        BreakSegmentResponse response = workSessionService.addBreakSegment(workSessionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "stopBreakSegment", summary = "Stop a Break Segment", description = """
                    Closes an open break segment within a work session, recording the break end time used to \
                    reduce the session's net worked duration.
                    Use this tool when a technician resumes work after a pause; do not use stopWorkSession, \
                    which ends the entire session.
                    Preconditions: the session and break segment must exist, the break must belong to that \
                    session and still be open, and the session must not be locked.
                    Required inputs: workSessionId (UUID) and breakSegmentId (UUID) as path parameters; there is \
                    no request body.
                    Emits a WORKORDER_WORK_SESSION_BREAK_STOP event.
                    Returns 404 when the session or break segment cannot be found, and 409 when the break is \
                    already stopped or the session is locked.
                    """)
    @ApiResponse(responseCode = "200", description = "Break segment stopped successfully")
    @ApiResponse(responseCode = "400", description = "Break segment not active or work session invalid")
    @ApiResponse(responseCode = "404", description = "Work session or break segment not found")
    @PostMapping("/{workSessionId}/breaks/{breakSegmentId}/stop")
    @EmitEvent(id = "WORKORDER_WORK_SESSION_BREAK_STOP", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"timekeeping:work_session:break_stop"})
    @PreAuthorize("hasAuthority('timekeeping:work_session:break_stop')")
    public ResponseEntity<BreakSegmentResponse> stopBreakSegment(
            @Parameter(description = "ID of the work session", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workSessionId,
            @Parameter(
                            description = "ID of the break segment to stop",
                            example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID breakSegmentId) {
        BreakSegmentResponse response = workSessionService.stopBreakSegment(workSessionId, breakSegmentId);
        return ResponseEntity.ok(response);
    }
}
