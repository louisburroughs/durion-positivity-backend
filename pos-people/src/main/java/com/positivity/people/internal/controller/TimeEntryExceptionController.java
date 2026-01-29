package com.positivity.people.internal.controller;

import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.entity.TimeEntryException;
import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.service.TimeEntryExceptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/v1/people/exceptions")
@Tag(name = "People - Exceptions", description = "Time entry exception APIs")
public class TimeEntryExceptionController {

    private final TimeEntryExceptionRepository exceptionRepository;
    private final TimeEntryExceptionService exceptionService;

    public TimeEntryExceptionController(TimeEntryExceptionRepository exceptionRepository,
            TimeEntryExceptionService exceptionService) {
        this.exceptionRepository = exceptionRepository;
        this.exceptionService = exceptionService;
    }

    @Operation(summary = "Create a time entry exception", description = "Create a new time entry exception record with validation of required fields.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<TimeEntryExceptionResponse> createException(@RequestBody TimeEntryExceptionRequest req) {
        TimeEntryException e = new TimeEntryException();
        e.setEmployeeId(req.getEmployeeId());
        e.setExceptionCode(req.getExceptionCode());
        if (req.getSeverity() != null) {
            try {
                e.setSeverity(com.positivity.people.internal.model.ExceptionSeverity.valueOf(req.getSeverity()));
            } catch (IllegalArgumentException iae) {
                e.setSeverity(com.positivity.people.internal.model.ExceptionSeverity.WARNING);
            }
        }
        e.setTimeEntryId(req.getTimeEntryId());
        e.setResolutionNotes(req.getResolutionNotes());
        if (req.getDetectedAt() != null) {
            e.setDetectedAt(req.getDetectedAt().toInstant());
        } else {
            e.setDetectedAt(java.time.Instant.now());
        }
        e.setStatus(com.positivity.people.internal.model.ExceptionStatus.OPEN);

        TimeEntryException saved = exceptionRepository.save(e);
        TimeEntryExceptionResponse resp = new TimeEntryExceptionResponse(saved.getExceptionId(), true, "created");
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "List exceptions, optional filter by employeeId", description = "Retrieve all exceptions or filter by a specific employee ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List returned")
    })
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<TimeEntryException>> listByEmployee(@RequestParam(required = false) String employeeId) {
        List<TimeEntryException> list;
        if (employeeId == null) {
            list = exceptionRepository.findAll();
        } else {
            list = exceptionRepository.findByEmployeeId(employeeId);
        }
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "Acknowledge an exception", description = "Mark an exception as acknowledged by the specified actor.")
    @PostMapping(value = "/{exceptionId}/acknowledge", produces = "application/json")
    public ResponseEntity<?> acknowledgeException(@PathVariable java.util.UUID exceptionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actor = userId != null ? userId : "system";
        boolean ok = exceptionService.actionException(exceptionId,
                com.positivity.people.internal.model.ExceptionStatus.ACKNOWLEDGED, actor, null, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse("NOT_FOUND",
                "exception not found", correlationId);
        return ResponseEntity.status(404).body(err);
    }

    @Operation(summary = "Resolve an exception", description = "Mark an exception as resolved with optional resolution notes.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception resolved successfully"),
            @ApiResponse(responseCode = "404", description = "Exception not found")
    })
    @PostMapping(value = "/{exceptionId}/resolve", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> resolveException(@PathVariable java.util.UUID exceptionId,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actor = userId != null ? userId : "system";
        String notes = null;
        if (body != null && body.containsKey("resolutionNotes"))
            notes = body.get("resolutionNotes");

        boolean ok = exceptionService.actionException(exceptionId, com.positivity.people.internal.model.ExceptionStatus.RESOLVED,
                actor, notes, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse("NOT_FOUND",
                "exception not found", correlationId);
        return ResponseEntity.status(404).body(err);
    }

    @Operation(summary = "Waive an exception", description = "Waive an exception with a reason. waiveReason is required.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception waived successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request - waiveReason is required"),
            @ApiResponse(responseCode = "404", description = "Exception not found")
    })
    @PostMapping(value = "/{exceptionId}/waive", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> waiveException(@PathVariable java.util.UUID exceptionId,
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (body == null || !body.containsKey("waiveReason") || body.get("waiveReason") == null
                || body.get("waiveReason").isBlank()) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse("INVALID_INPUT",
                    "waiveReason is required and cannot be blank", correlationId);
            return ResponseEntity.status(400).body(err);
        }

        String actor = userId != null ? userId : "system";
        String waiveReason = body.get("waiveReason");

        boolean ok = exceptionService.actionException(exceptionId, com.positivity.people.internal.model.ExceptionStatus.WAIVED,
                actor, waiveReason, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse("NOT_FOUND",
                "exception not found", correlationId);
        return ResponseEntity.status(404).body(err);
    }
}
