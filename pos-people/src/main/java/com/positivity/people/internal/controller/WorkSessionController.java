package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionRequest;
import com.positivity.people.service.WorkSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.UUID;

@Slf4j
@Tag(name = "Work Sessions API", description = "Operations for managing work sessions and breaks")
@RestController
@RequestMapping("/v1/people/workSessions")
@RequiredArgsConstructor
public class WorkSessionController {

	private final WorkSessionService workSessionService;

	@Operation(summary = "Start work session", description = "Create/start a work session for a person.")
	@ApiResponse(responseCode = "200", description = "Work session started successfully.")
	@EmitEvent(id = "PEOPLE_WORK_SESSION_START", apiVersion = "1")
	@PostMapping("/start")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<WorkSessionDto> startWorkSession(@Parameter(
			description = "Work session start request body") @Valid @RequestBody @NonNull WorkSessionRequest request) {
		log.info("Starting work session for personId={}", request.getPersonId());
		WorkSessionDto response = workSessionService.startSession(request.getPersonId());
		return ResponseEntity.ok(response);
	}

	@Operation(summary = "Stop work session", description = "Stop an active work session.")
	@ApiResponse(responseCode = "200", description = "Work session stopped successfully.")
	@EmitEvent(id = "PEOPLE_WORK_SESSION_STOP", apiVersion = "1")
	@PostMapping("/stop")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<WorkSessionDto> stopWorkSession(@Parameter(
			description = "Work session stop request body") @Valid @RequestBody @NonNull WorkSessionRequest request) {
		log.info("Stopping work session for personId={}", request.getPersonId());
		WorkSessionDto response = workSessionService.stopSession(request.getPersonId());
		return ResponseEntity.ok(response);
	}

	@Operation(summary = "Start work session break", description = "Start a break within an active work session.")
	@ApiResponse(responseCode = "200", description = "Break started successfully.")
	@ApiResponse(responseCode = "404", description = "Work session not found.")
	@EmitEvent(id = "PEOPLE_WORK_SESSION_BREAK_START", apiVersion = "1")
	@PostMapping("/{id}/breaks/start")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BreakDto> startWorkSessionBreak(@Parameter(description = "Work session ID",
			example = "7b1f5a9d-0c2b-4c6d-9a5a-5f8e7c3a9b1c") @PathVariable @NonNull UUID id) {
		log.info("Starting break for work session ID: {}", id);
		BreakDto response = workSessionService.startBreak(id);
		return ResponseEntity.ok(response);
	}

	@Operation(summary = "Stop work session break", description = "End a break within a work session.")
	@ApiResponse(responseCode = "200", description = "Break stopped successfully.")
	@ApiResponse(responseCode = "404", description = "Work session or break not found.")
	@EmitEvent(id = "PEOPLE_WORK_SESSION_BREAK_STOP", apiVersion = "1")
	@PostMapping("/{id}/breaks/stop")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<BreakDto> stopWorkSessionBreak(@Parameter(description = "Work session ID",
			example = "7b1f5a9d-0c2b-4c6d-9a5a-5f8e7c3a9b1c") @PathVariable @NonNull UUID id) {
		log.info("Stopping break for work session ID: {}", id);
		BreakDto response = workSessionService.stopBreak(id);
		return ResponseEntity.ok(response);
	}

}
