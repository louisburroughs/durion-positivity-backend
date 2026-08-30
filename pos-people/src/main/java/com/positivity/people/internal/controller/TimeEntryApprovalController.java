package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.TimeEntryDecisionBatchRequest;
import com.positivity.people.internal.dto.TimeEntryDecisionResponse;
import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.dto.TimeEntrySummary;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.security.PeoplePermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/v1/people/timeEntries")
@Tag(
        name = "Time Entry Approval API",
        description = "List, approve and reject attendance time entries (clock-in, clock-out, breaks)")
public class TimeEntryApprovalController {

    private final com.positivity.people.internal.service.TimeEntryService timeEntryService;

    @Operation(
            operationId = "listTimeEntries",
            summary = "List Attendance Time Entries For Approval",
            description = """
                    Returns a page of attendance time entries — clock-in, clock-out and the break minutes inside \
                    that window — with each entry's status and approval decision, oldest submission first.
                    Use this tool to build the approvals queue or to find the timeEntryId an approve or reject \
                    call needs; do not use listWorkexecWorkSessions, which returns time a technician spent on a \
                    workorder task. A time entry is attendance only and carries no workorder reference, so this \
                    endpoint cannot answer how long a job took.
                    Preconditions: none; a page with an empty items list is returned rather than an error when \
                    nothing matches.
                    Required inputs: none are mandatory. status, workDate, employeeId and locationId each narrow \
                    the result and are unfiltered when omitted; workDate is a calendar day resolved in timeZone, \
                    which defaults to UTC; page defaults to 0 and size to 20 with a maximum of 100.
                    Emits a PEOPLE_TIME_ENTRY_LIST audit event but changes no state.
                    Returns 200 with the page envelope, and 400 when timeZone is not a known zone id or the \
                    paging parameters are out of range.
                    """)
    @ApiResponse(responseCode = "200", description = "Time entries returned (possibly empty)")
    @ApiResponse(responseCode = "400", description = "Invalid filter or paging parameter")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_LIST", apiVersion = "1")
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeEntry:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEENTRY_VIEW + "')")
    public ResponseEntity<PagedResponse<TimeEntrySummary>> listTimeEntries(
            @Parameter(description = "Keep only entries in this status; omit for every status")
                    @RequestParam(required = false)
                    TimeEntryStatus status,
            @Parameter(description = "Calendar day of the clock-in, resolved in timeZone", example = "2026-01-15")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate workDate,
            @Parameter(
                            description = "Zone whose calendar day workDate names; defaults to UTC",
                            example = "America/Chicago")
                    @RequestParam(required = false)
                    String timeZone,
            @Parameter(description = "Keep only this person's entries") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Keep only entries clocked at this location") @RequestParam(required = false)
                    UUID locationId,
            @Parameter(description = "Zero-based page index") @PositiveOrZero @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(description = "Page size, up to 100") @Positive @Max(100) @RequestParam(defaultValue = "20")
                    int size) {
        return ResponseEntity.ok(timeEntryService.listTimeEntries(
                status, workDate, resolveZone(timeZone), employeeId, locationId, page, size));
    }

    @Operation(operationId = "getTimeEntry", summary = "Get One Attendance Time Entry", description = """
                    Returns a single attendance time entry by id, with its clock-in, clock-out, break minutes, \
                    status and approval decision.
                    Use this tool for the detail view opened from an approvals-queue row; do not use \
                    listTimeEntries, which pages over many entries, and do not use getTimeEntryAdjustments, which \
                    returns the proposed corrections attached to an entry rather than the entry itself.
                    Preconditions: an entry must exist for the supplied timeEntryId.
                    Required inputs: timeEntryId (UUID) path parameter; timeZone is optional and defaults to UTC, \
                    affecting only the derived workDate.
                    Emits a PEOPLE_TIME_ENTRY_GET audit event but changes no state.
                    Returns 404 when no entry has that id, and 400 when timeZone is not a known zone id.
                    """)
    @ApiResponse(responseCode = "200", description = "Time entry returned")
    @ApiResponse(responseCode = "400", description = "Invalid timeZone")
    @ApiResponse(responseCode = "404", description = "Time entry not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_GET", apiVersion = "1")
    @GetMapping("/{timeEntryId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeEntry:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEENTRY_VIEW + "')")
    public ResponseEntity<TimeEntrySummary> getTimeEntry(
            @PathVariable UUID timeEntryId,
            @Parameter(description = "Zone the derived workDate is resolved in; defaults to UTC")
                    @RequestParam(required = false)
                    String timeZone) {
        return ResponseEntity.ok(timeEntryService.getTimeEntry(timeEntryId, resolveZone(timeZone)));
    }

    /**
     * A zone id the caller typed is request data, so an unknown one is a 400 rather than the 500
     * an unhandled {@link DateTimeException} would produce.
     */
    private ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Unknown timeZone: " + timeZone, ex);
        }
    }

    @Operation(
            operationId = "approveTimeEntriesBatch",
            summary = "Batch Approve Submitted Time Entries",
            description = """
                    Approves a batch of time entries, marking each SUBMITTED or PENDING_APPROVAL entry APPROVED and \
                    stamping the approver and approval time; pos-people is authoritative for approval execution.
                    Use this tool for supervisor approval of individually selected time entries; do not use \
                    rejectTimeEntriesBatch, which rejects them, and do not use approveTimePeriod, which approves a \
                    whole pay period per person.
                    Preconditions: each entry must exist and be in SUBMITTED or PENDING_APPROVAL status, and the \
                    caller needs the people:timeEntry:approve authority for individual entries to succeed.
                    Required inputs: decisions, a non-empty list of objects each carrying timeEntryId (UUID string); \
                    an optional X-Correlation-Id header is recorded in the audit trail.
                    Emits a PEOPLE_TIME_ENTRY_APPROVE event and writes an audit row per decision.
                    Returns 200 with a per-entry result list (failure codes NOT_FOUND, ENTRY_NOT_PENDING, FORBIDDEN) \
                    rather than failing the whole batch, and 400 when the decisions list is missing or empty.
                    """)
    @ApiResponse(responseCode = "200", description = "Time entries approved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - decisions required")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_APPROVE", apiVersion = "1")
    @PostMapping("/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeEntry:approve"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEENTRY_APPROVE + "')")
    public ResponseEntity<Object> approveTimeEntries(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch of approval decisions, one element per time entry to approve; rejectionReason is ignored here.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Approve two entries", value = """
                                                                    {"decisions":[
                                                                      {"timeEntryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"},
                                                                      {"timeEntryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}]}
                                                                    """)))
                    @RequestBody
                    @Valid
                    TimeEntryDecisionBatchRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        List<String> ids = request.getDecisions().stream()
                .map(TimeEntryDecisionBatchRequest.Decision::getTimeEntryId)
                .toList();

        List<TimeEntryDecisionResult> results = timeEntryService.approveEntries(ids, correlationId);

        TimeEntryDecisionResponse resp = new TimeEntryDecisionResponse(results);
        return ResponseEntity.ok(resp);
    }

    @Operation(
            operationId = "rejectTimeEntriesBatch",
            summary = "Batch Reject Submitted Time Entries",
            description = """
                    Rejects a batch of time entries, marking each SUBMITTED or PENDING_APPROVAL entry REJECTED with \
                    the supplied reason stored on the entry.
                    Use this tool to send individually selected entries back with a reason; do not use \
                    approveTimeEntriesBatch, which approves them, and do not use rejectTimePeriod, which rejects a \
                    whole pay period per person.
                    Preconditions: each entry must exist and be in SUBMITTED or PENDING_APPROVAL status, and the \
                    caller needs the people:timeEntry:reject authority for individual entries to succeed.
                    Required inputs: decisions, a non-empty list where every element carries timeEntryId and a \
                    non-blank rejectionReason; one missing reason fails the entire request before any entry is \
                    touched.
                    Emits a PEOPLE_TIME_ENTRY_REJECT event and writes an audit row per decision.
                    Returns 200 with a per-entry result list (failure codes NOT_FOUND, ENTRY_NOT_PENDING, \
                    FORBIDDEN), and 400 when any decision lacks a rejectionReason.
                    """)
    @ApiResponse(responseCode = "200", description = "Time entries rejected successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - rejectionReason required for all decisions")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_REJECT", apiVersion = "1")
    @PostMapping("/reject")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeEntry:reject"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEENTRY_REJECT + "')")
    public ResponseEntity<Object> rejectTimeEntries(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch of rejection decisions; every element must carry a non-blank rejectionReason.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Reject one entry", value = """
                                                                    {"decisions":[
                                                                      {"timeEntryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                       "rejectionReason":"Incomplete work details"}]}
                                                                    """)))
                    @RequestBody
                    @Valid
                    TimeEntryDecisionBatchRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        // Extract rejection reasons map and pass to service
        java.util.Map<String, String> rejectionReasons = new java.util.HashMap<>();
        for (TimeEntryDecisionBatchRequest.Decision d : request.getDecisions()) {
            rejectionReasons.put(d.getTimeEntryId(), d.getRejectionReason());
        }

        List<String> ids = request.getDecisions().stream()
                .map(TimeEntryDecisionBatchRequest.Decision::getTimeEntryId)
                .toList();

        List<TimeEntryDecisionResult> results = timeEntryService.rejectEntries(ids, rejectionReasons, correlationId);

        TimeEntryDecisionResponse resp = new TimeEntryDecisionResponse(results);
        return ResponseEntity.ok(resp);
    }
}
