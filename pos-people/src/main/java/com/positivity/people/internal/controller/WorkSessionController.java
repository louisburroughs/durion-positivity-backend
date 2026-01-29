package com.positivity.people.internal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Work Sessions API", description = "Operations for managing work sessions and breaks")
@RestController
@RequestMapping("/v1/people/workSessions")
@RequiredArgsConstructor
public class WorkSessionController {

    @Operation(summary = "Start work session", description = "Create/start a work session for a person.")
    @ApiResponse(responseCode = "200", description = "Work session started successfully.")
    @PostMapping("/start")
    public ResponseEntity<Object> startWorkSession(
            @Parameter(description = "Work session start request body") @RequestBody(required = false) Object request) {
        log.info("Starting work session");
        // TODO: Implement work session start logic
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Stop work session", description = "Stop an active work session.")
    @ApiResponse(responseCode = "200", description = "Work session stopped successfully.")
    @PostMapping("/stop")
    public ResponseEntity<Object> stopWorkSession(
            @Parameter(description = "Work session stop request body") @RequestBody(required = false) Object request) {
        log.info("Stopping work session");
        // TODO: Implement work session stop logic
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Start work session break", description = "Start a break within an active work session.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Break started successfully."),
            @ApiResponse(responseCode = "404", description = "Work session not found.")
    })
    @PostMapping("/{id}/breaks/start")
    public ResponseEntity<Object> startWorkSessionBreak(
            @Parameter(description = "Work session ID", example = "1") @PathVariable Long id,
            @Parameter(description = "Break start request body") @RequestBody(required = false) Object request) {
        log.info("Starting break for work session ID: {}", id);
        // TODO: Implement break start logic
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Stop work session break", description = "End a break within a work session.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Break stopped successfully."),
            @ApiResponse(responseCode = "404", description = "Work session or break not found.")
    })
    @PostMapping("/{id}/breaks/stop")
    public ResponseEntity<Object> stopWorkSessionBreak(
            @Parameter(description = "Work session ID", example = "1") @PathVariable Long id,
            @Parameter(description = "Break stop request body") @RequestBody(required = false) Object request) {
        log.info("Stopping break for work session ID: {}", id);
        // TODO: Implement break stop logic
        return ResponseEntity.ok().build();
    }
}
