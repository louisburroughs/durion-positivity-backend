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

    @Operation(
            summary = "Start a work session",
            description = "Technician starts a work session on a work order. "
                    + "Creates an IN_PROGRESS work session and records the start time.")
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
    public ResponseEntity<WorkSessionResponse> startWorkSession(@Valid @RequestBody StartWorkSessionRequest request) {
        WorkSessionResponse response = workSessionService.startSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Stop a work session",
            description = "Technician stops an active work session. "
                    + "Records end time and transitions the session to COMPLETED status.")
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
            @Valid @RequestBody StopWorkSessionRequest request) {
        WorkSessionResponse response = workSessionService.stopSession(workSessionId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Start a break segment",
            description = "Technician records the start of a break within an active work session.")
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
            @Valid @RequestBody AddBreakSegmentRequest request) {
        BreakSegmentResponse response = workSessionService.addBreakSegment(workSessionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Stop a break segment",
            description = "Technician records the end of a break segment within an active work session.")
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
