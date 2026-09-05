package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionRequest;
import com.positivity.people.internal.dto.WorkSessionSubmitRequest;
import com.positivity.people.internal.service.WorkSessionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Work Sessions API", description = "Operations for managing work sessions and breaks")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/people/workSessions")
@RequiredArgsConstructor
public class WorkSessionController {

    private final WorkSessionService workSessionService;

    @Operation(operationId = "startWorkSession", summary = "Start A Work Session For Person", description = """
                    Starts a new ACTIVE work session for a person, stamping the start time from the server clock and \
                    the actor from the security context.
                    Use this tool when a person clocks in for the day; do not use startWorkSessionBreak, which \
                    pauses an already running session.
                    Preconditions: the person must exist in the identity replica, and the person must have no \
                    session that is still open.
                    Required inputs: personId (UUID) in the body; the body's actor field is ignored, because the \
                    recorded actor is always the authenticated username.
                    Emits a PEOPLE_WORK_SESSION_START event.
                    Returns 404 when the person is unknown, and 409 when an active session already exists for the \
                    person, including under concurrent start races.
                    """)
    @ApiResponse(responseCode = "200", description = "Work session started successfully.")
    @EmitEvent(id = "PEOPLE_WORK_SESSION_START", apiVersion = "1")
    @PostMapping("/start")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkSessionDto> startWorkSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Identifies the person clocking in; the actor field is ignored in favour of"
                                    + " the authenticated username.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Clock in", value = """
                                                                    {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    WorkSessionRequest request) {
        log.info("Starting work session for personId(mask)={}", maskForLog(request.getPersonId()));
        WorkSessionDto response = workSessionService.startSession(request.getPersonId());
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "stopWorkSession", summary = "Stop An Active Work Session", description = """
                    Stops a person's open work session, setting status ENDED and closing any break still open at the \
                    same timestamp.
                    Use this tool when a person clocks out; do not use submitWorkSession, which sends an already \
                    ENDED session for approval.
                    Preconditions: the person must have an open session; sessions are keyed by person here, not by \
                    session id.
                    Required inputs: personId (UUID) in the body; the body's actor field is ignored in favour of the \
                    authenticated username.
                    Emits a PEOPLE_WORK_SESSION_STOP event.
                    Returns 404 when no active session exists for the person.
                    """)
    @ApiResponse(responseCode = "200", description = "Work session stopped successfully.")
    @EmitEvent(id = "PEOPLE_WORK_SESSION_STOP", apiVersion = "1")
    @PostMapping("/stop")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkSessionDto> stopWorkSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Identifies the person clocking out; the open session is found by person"
                                    + " id, not session id.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Clock out", value = """
                                                                    {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    WorkSessionRequest request) {
        log.info("Stopping work session for personId(mask)={}", maskForLog(request.getPersonId()));
        WorkSessionDto response = workSessionService.stopSession(request.getPersonId());
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "startWorkSessionBreak", summary = "Start A Break In Work Session", description = """
                    Starts a break inside an active work session, stamping the start time from the server clock.
                    Use this tool when a person pauses work; do not use stopWorkSession, which ends the whole \
                    session rather than pausing it.
                    Preconditions: the session must exist and still be open, and no break may already be open on it.
                    Required inputs: the work session id (UUID) as the path parameter; there is no request body.
                    Emits a PEOPLE_WORK_SESSION_BREAK_START event.
                    Returns 404 when no open session exists for the id, and 409 when a break is already open, \
                    including under concurrent break-start races.
                    """)
    @ApiResponse(responseCode = "200", description = "Break started successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Work session not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_WORK_SESSION_BREAK_START", apiVersion = "1")
    @PostMapping("/{id}/breaks/start")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BreakDto> startWorkSessionBreak(
            @Parameter(description = "Work session ID", example = "7b1f5a9d-0c2b-4c6d-9a5a-5f8e7c3a9b1c")
                    @PathVariable
                    @NonNull
                    UUID id) {
        log.info("Starting break for work session ID(mask): {}", maskForLog(id));
        BreakDto response = workSessionService.startBreak(id);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "stopWorkSessionBreak", summary = "Stop The Open Work Session Break", description = """
                    Ends the open break on a work session, stamping the end time from the server clock.
                    Use this tool when a person returns from a break; do not use stopWorkSession, which ends the \
                    session and auto-closes any open break itself.
                    Preconditions: the session must have an open break with no recorded end time.
                    Required inputs: the work session id (UUID) as the path parameter; there is no request body.
                    Emits a PEOPLE_WORK_SESSION_BREAK_STOP event.
                    Returns 409 when no open break exists for the session, including when the session id itself is \
                    unknown.
                    """)
    @ApiResponse(responseCode = "200", description = "Break stopped successfully.")
    @ApiResponse(
            responseCode = "409",
            description = "No open break exists for the session, including when the session id itself is unknown.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_WORK_SESSION_BREAK_STOP", apiVersion = "1")
    @PostMapping("/{id}/breaks/stop")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BreakDto> stopWorkSessionBreak(
            @Parameter(description = "Work session ID", example = "7b1f5a9d-0c2b-4c6d-9a5a-5f8e7c3a9b1c")
                    @PathVariable
                    @NonNull
                    UUID id) {
        log.info("Stopping break for work session ID(mask): {}", maskForLog(id));
        BreakDto response = workSessionService.stopBreak(id);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "submitWorkSession", summary = "Submit An Ended Work Session", description = """
                    Submits an ENDED work session for approval, recording billable and break minute totals and \
                    marking the session SUBMITTED.
                    Use this tool after stopWorkSession has ended the session; do not use approveTimeEntriesBatch, \
                    which decides time entries, not work sessions.
                    Preconditions: the session must exist and be in ENDED status; ACTIVE or already SUBMITTED \
                    sessions are rejected.
                    Required inputs: the work session id (UUID) path parameter and a body with billableMinutes and \
                    breakMinutes (both zero or greater) and submittedAt (ISO-8601 instant).
                    Emits a PEOPLE_WORK_SESSION_SUBMIT event.
                    Returns 404 when the session does not exist, and 409 when the session is not in ENDED status.
                    """)
    @ApiResponse(responseCode = "200", description = "Work session submitted successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Work session not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Work session is not in a submittable state.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "PEOPLE_WORK_SESSION_SUBMIT", apiVersion = "1")
    @PostMapping("/{id}/submit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkSessionDto> submitWorkSession(
            @Parameter(description = "Work session ID", example = "7b1f5a9d-0c2b-4c6d-9a5a-5f8e7c3a9b1c")
                    @PathVariable
                    @NonNull
                    UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Billable and break minute totals plus the submission timestamp for the"
                                    + " ended session.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Full day with lunch break", value = """
                                                                    {"billableMinutes":480,"breakMinutes":30,
                                                                     "submittedAt":"2026-02-16T17:00:00Z"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    WorkSessionSubmitRequest request) {
        log.info("Submitting work session ID(mask): {}", maskForLog(id));
        WorkSessionDto response = workSessionService.submitSession(id, request);
        return ResponseEntity.ok(response);
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
