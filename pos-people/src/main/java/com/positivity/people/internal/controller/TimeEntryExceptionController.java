package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryException;
import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.service.TimeEntryExceptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private static final String WAIVE_REASON = "waiveReason";
    private static final String EXCEPTION_NOT_FOUND = "exception not found";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String SYSTEM = "system";
    private final TimeEntryExceptionService exceptionService;

    public TimeEntryExceptionController(TimeEntryExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @Operation(summary = "Create a time entry exception", description = "Create a new time entry exception record with validation of required fields.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_CREATE", apiVersion = "1")
    @PostMapping(consumes = "application/json", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeException:create')")
    public ResponseEntity<TimeEntryExceptionResponse> createException(@RequestBody TimeEntryExceptionRequest req) {
        TimeEntryExceptionResponse resp = exceptionService.createException(req);
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "List exceptions, optional filter by employeeId", description = "Retrieve all exceptions or filter by a specific employee ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List returned")
    })
    @GetMapping(produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeException:view')")
    public ResponseEntity<List<TimeEntryException>> listByEmployee(@RequestParam(required = false) String employeeId) {
        List<TimeEntryException> list = exceptionService.listByEmployee(employeeId);
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "Acknowledge an exception", description = "Mark an exception as acknowledged by the specified actor.")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_ACKNOWLEDGE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/acknowledge", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeException:acknowledge')")
    public ResponseEntity<Object> acknowledgeException(@PathVariable java.util.UUID exceptionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actor = userId != null ? userId : SYSTEM;
        boolean ok = exceptionService.actionException(exceptionId,
                com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED, actor, null, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                NOT_FOUND,
                EXCEPTION_NOT_FOUND, correlationId);
        return ResponseEntity.status(404).body(err);
    }

    @Operation(summary = "Resolve an exception", description = "Mark an exception as resolved with optional resolution notes.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception resolved successfully"),
            @ApiResponse(responseCode = "404", description = "Exception not found")
    })
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_RESOLVE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/resolve", consumes = "application/json", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeException:resolve')")
    public ResponseEntity<Object> resolveException(@PathVariable java.util.UUID exceptionId,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actor = userId != null ? userId : SYSTEM;
        String notes = null;
        if (body != null && body.containsKey("resolutionNotes"))
            notes = body.get("resolutionNotes");

        boolean ok = exceptionService.actionException(exceptionId,
                com.positivity.people.internal.enums.ExceptionStatus.RESOLVED,
                actor, notes, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                NOT_FOUND,
                EXCEPTION_NOT_FOUND, correlationId);
        return ResponseEntity.status(404).body(err);
    }

    @Operation(summary = "Waive an exception", description = "Waive an exception with a reason. waiveReason is required.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception waived successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request - waiveReason is required"),
            @ApiResponse(responseCode = "404", description = "Exception not found")
    })
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_WAIVE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/waive", consumes = "application/json", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeException:resolve')")
    public ResponseEntity<Object> waiveException(@PathVariable java.util.UUID exceptionId,
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (body == null || !body.containsKey(WAIVE_REASON) || body.get(WAIVE_REASON) == null
                || body.get(WAIVE_REASON).isBlank()) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                    "INVALID_INPUT",
                    "waiveReason is required and cannot be blank", correlationId);
            return ResponseEntity.status(400).body(err);
        }

        String actor = userId != null ? userId : SYSTEM;
        String waiveReason = body.get(WAIVE_REASON);

        boolean ok = exceptionService.actionException(exceptionId,
                com.positivity.people.internal.enums.ExceptionStatus.WAIVED,
                actor, waiveReason, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                NOT_FOUND,
                EXCEPTION_NOT_FOUND, correlationId);
        return ResponseEntity.status(404).body(err);
    }
}
