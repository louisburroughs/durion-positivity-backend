package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryDecisionBatchRequest;
import com.positivity.people.internal.dto.TimeEntryDecisionResponse;
import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.security.PeoplePermissions;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people/timeEntries")
@Tag(name = "Time Entry Approval API", description = "Approve/reject time entries (batch)")
public class TimeEntryApprovalController {

    private final com.positivity.people.service.TimeEntryService timeEntryService;

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
