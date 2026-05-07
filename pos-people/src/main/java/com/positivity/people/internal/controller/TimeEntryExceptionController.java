package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryException;
import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResolveRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.dto.TimeEntryExceptionWaiveRequest;
import com.positivity.people.service.TimeEntryExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people/exceptions")
@Tag(name = "People - Exceptions", description = "Time entry exception APIs")
public class TimeEntryExceptionController {

    private final TimeEntryExceptionService exceptionService;

    @Operation(
            summary = "Create a time entry exception",
            description = "Create a new time entry exception record with validation of required fields.")
    @ApiResponse(responseCode = "200", description = "Exception created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_CREATE", apiVersion = "1")
    @PostMapping(consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:create"})
    @PreAuthorize("hasAuthority('people:timeException:create')")
    public ResponseEntity<TimeEntryExceptionResponse> createException(
            @Valid @RequestBody TimeEntryExceptionRequest req) {
        TimeEntryExceptionResponse resp = exceptionService.createException(req);
        return ResponseEntity.ok(resp);
    }

    @Operation(
            summary = "List exceptions, optional filter by employeeId",
            description = "Retrieve all exceptions or filter by a specific employee ID.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @GetMapping(produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:view"})
    @PreAuthorize("hasAuthority('people:timeException:view')")
    public ResponseEntity<List<TimeEntryException>> listByEmployee(@RequestParam(required = false) String employeeId) {
        List<TimeEntryException> list = exceptionService.listByEmployee(employeeId);
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "Acknowledge an exception", description = "Mark an exception as acknowledged.")
    @ApiResponse(responseCode = "200", description = "Exception acknowledged successfully")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_ACKNOWLEDGE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/acknowledge", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:acknowledge"})
    @PreAuthorize("hasAuthority('people:timeException:acknowledge')")
    public ResponseEntity<Object> acknowledgeException(
            @PathVariable java.util.UUID exceptionId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        exceptionService.actionException(
                exceptionId, com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED, null, correlationId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Resolve an exception",
            description = "Mark an exception as resolved with optional resolution notes.")
    @ApiResponse(responseCode = "200", description = "Exception resolved successfully")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_RESOLVE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/resolve", consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:resolve"})
    @PreAuthorize("hasAuthority('people:timeException:resolve')")
    public ResponseEntity<Object> resolveException(
            @PathVariable java.util.UUID exceptionId,
            @Valid @RequestBody(required = false) TimeEntryExceptionResolveRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String notes = request != null ? request.getResolutionNotes() : null;
        exceptionService.actionException(
                exceptionId, com.positivity.people.internal.enums.ExceptionStatus.RESOLVED, notes, correlationId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Waive an exception",
            description = "Waive an exception with a reason. waiveReason is required.")
    @ApiResponse(responseCode = "200", description = "Exception waived successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - waiveReason is required")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_WAIVE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/waive", consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:resolve"})
    @PreAuthorize("hasAuthority('people:timeException:resolve')")
    public ResponseEntity<Object> waiveException(
            @PathVariable java.util.UUID exceptionId,
            @Valid @RequestBody TimeEntryExceptionWaiveRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        exceptionService.actionException(
                exceptionId,
                com.positivity.people.internal.enums.ExceptionStatus.WAIVED,
                request.getWaiveReason(),
                correlationId);
        return ResponseEntity.ok().build();
    }
}
