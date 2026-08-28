package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryException;
import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResolveRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.dto.TimeEntryExceptionWaiveRequest;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.TimeEntryExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @Operation(operationId = "createTimeEntryException", summary = "Create A Time Entry Exception", description = """
                    Records a timekeeping exception in OPEN status for an employee, such as a missed clock-out.
                    Use this tool when a timekeeping anomaly is detected; do not use resolveTimeEntryException or \
                    waiveTimeEntryException, which close an existing exception.
                    Preconditions: none are checked against other records; employeeId and timeEntryId are stored as \
                    given without referential validation.
                    Required inputs: employeeId and exceptionCode; severity accepts WARNING or BLOCKING and silently \
                    falls back to WARNING on any other value, and detectedAt defaults to the current server time.
                    Emits a PEOPLE_TIME_ENTRY_EXCEPTION_CREATE event; the exception starts in OPEN status.
                    Returns 200 with the new exceptionId, and 400 when the JSON payload is malformed.
                    """)
    @ApiResponse(responseCode = "200", description = "Exception created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_CREATE", apiVersion = "1")
    @PostMapping(consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:create"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEEXCEPTION_CREATE + "')")
    public ResponseEntity<TimeEntryExceptionResponse> createException(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Timekeeping anomaly to record against an employee, optionally tied to a"
                                    + " specific time entry.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Missed clock-out", value = """
                                                                    {"employeeId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "exceptionCode":"MISSED_CLOCK_OUT",
                                                                     "severity":"BLOCKING",
                                                                     "timeEntryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "resolutionNotes":"Employee forgot to clock out at end of shift",
                                                                     "detectedAt":"2026-02-17T08:30:00-05:00"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    TimeEntryExceptionRequest req) {
        TimeEntryExceptionResponse resp = exceptionService.createException(req);
        return ResponseEntity.ok(resp);
    }

    @Operation(
            operationId = "listTimeEntryExceptions",
            summary = "List Time Entry Exceptions By Employee",
            description = """
                    Lists time entry exceptions, either all of them or those recorded for one employee.
                    Use this tool to review open and historical exceptions; use createTimeEntryException instead to \
                    record a new one.
                    Preconditions: none; when employeeId is omitted every exception in the system is returned \
                    without pagination.
                    Required inputs: none; employeeId (string) is an optional filter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when nothing matches.
                    """)
    @ApiResponse(responseCode = "200", description = "List returned")
    @GetMapping(produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEEXCEPTION_VIEW + "')")
    public ResponseEntity<List<TimeEntryException>> listByEmployee(@RequestParam(required = false) String employeeId) {
        List<TimeEntryException> list = exceptionService.listByEmployee(employeeId);
        return ResponseEntity.ok(list);
    }

    @Operation(
            operationId = "acknowledgeTimeEntryException",
            summary = "Acknowledge An Open Time Entry Exception",
            description = """
                    Marks an OPEN time entry exception as ACKNOWLEDGED, stamping the acting user and timestamp.
                    Use this tool to signal an exception has been seen but not yet fixed; use \
                    resolveTimeEntryException or waiveTimeEntryException instead to close it.
                    Preconditions: the exception must exist and must not already be RESOLVED or WAIVED, which are \
                    terminal states.
                    Required inputs: exceptionId (UUID) path parameter; there is no request body, and an optional \
                    X-Correlation-Id header is carried into the audit trail.
                    Emits a PEOPLE_TIME_ENTRY_EXCEPTION_ACKNOWLEDGE event and writes an EXCEPTION_ACKNOWLEDGED \
                    audit row.
                    Returns 404 when the exception does not exist, and 400 when it is already RESOLVED or WAIVED.
                    """)
    @ApiResponse(responseCode = "200", description = "Exception acknowledged successfully")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_ACKNOWLEDGE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/acknowledge", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:acknowledge"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEEXCEPTION_ACKNOWLEDGE + "')")
    public ResponseEntity<Object> acknowledgeException(
            @PathVariable java.util.UUID exceptionId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        exceptionService.actionException(
                exceptionId, com.positivity.people.internal.enums.ExceptionStatus.ACKNOWLEDGED, null, correlationId);
        return ResponseEntity.ok().build();
    }

    @Operation(operationId = "resolveTimeEntryException", summary = "Resolve A Time Entry Exception", description = """
                    Marks a time entry exception as RESOLVED with optional resolution notes, stamping the acting \
                    user and timestamp.
                    Use this tool when the underlying issue has been fixed; use waiveTimeEntryException instead to \
                    close it without a fix, or acknowledgeTimeEntryException to flag it as merely seen.
                    Preconditions: the exception must exist and must not already be RESOLVED or WAIVED, which are \
                    terminal states.
                    Required inputs: exceptionId (UUID) path parameter; the body is optional and may carry \
                    resolutionNotes.
                    Emits a PEOPLE_TIME_ENTRY_EXCEPTION_RESOLVE event and writes an EXCEPTION_RESOLVED audit row.
                    Returns 404 when the exception does not exist, and 400 when it is already RESOLVED or WAIVED.
                    """)
    @ApiResponse(responseCode = "200", description = "Exception resolved successfully")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_RESOLVE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/resolve", consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:resolve"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEEXCEPTION_RESOLVE + "')")
    public ResponseEntity<Object> resolveException(
            @PathVariable java.util.UUID exceptionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional resolution details; when present, resolutionNotes replaces the"
                                    + " notes stored on the exception.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Resolution with notes", value = """
                                                                    {"resolutionNotes":"Corrected the clock-out time manually"}
                                                                    """)))
                    @Valid
                    @RequestBody(required = false)
                    TimeEntryExceptionResolveRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String notes = request != null ? request.getResolutionNotes() : null;
        exceptionService.actionException(
                exceptionId, com.positivity.people.internal.enums.ExceptionStatus.RESOLVED, notes, correlationId);
        return ResponseEntity.ok().build();
    }

    @Operation(operationId = "waiveTimeEntryException", summary = "Waive A Time Entry Exception", description = """
                    Marks a time entry exception as WAIVED with a mandatory reason, closing it without a correction.
                    Use this tool to deliberately dismiss an exception; use resolveTimeEntryException instead when \
                    the underlying issue was actually fixed.
                    Preconditions: the exception must exist and must not already be RESOLVED or WAIVED, which are \
                    terminal states.
                    Required inputs: exceptionId (UUID) path parameter and a body with a non-blank waiveReason, \
                    which is stored as the exception's resolution notes.
                    Emits a PEOPLE_TIME_ENTRY_EXCEPTION_WAIVE event and writes an EXCEPTION_WAIVED audit row.
                    Returns 404 when the exception does not exist, and 400 when waiveReason is blank or the \
                    exception is already RESOLVED or WAIVED.
                    """)
    @ApiResponse(responseCode = "200", description = "Exception waived successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - waiveReason is required")
    @ApiResponse(responseCode = "404", description = "Exception not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_EXCEPTION_WAIVE", apiVersion = "1")
    @PostMapping(value = "/{exceptionId}/waive", consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeException:resolve"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEEXCEPTION_RESOLVE + "')")
    public ResponseEntity<Object> waiveException(
            @PathVariable java.util.UUID exceptionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Waiver justification; the reason is mandatory and becomes the exception's"
                                    + " resolution notes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Waive for device outage", value = """
                                                                    {"waiveReason":"Known device outage; entry validated manually"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    TimeEntryExceptionWaiveRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        exceptionService.actionException(
                exceptionId,
                com.positivity.people.internal.enums.ExceptionStatus.WAIVED,
                request.getWaiveReason(),
                correlationId);
        return ResponseEntity.ok().build();
    }
}
