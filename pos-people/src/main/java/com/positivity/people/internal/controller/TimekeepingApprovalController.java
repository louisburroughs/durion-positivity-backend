package com.positivity.people.internal.controller;

import com.positivity.people.internal.dto.ApprovalPersonDto;
import com.positivity.people.internal.dto.RejectTimePeriodRequest;
import com.positivity.people.internal.dto.TimePeriodApprovalDto;
import com.positivity.people.internal.dto.TimePeriodDecisionResponse;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimekeepingEntryDto;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.TimekeepingApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people/timekeeping")
@Tag(name = "Timekeeping Approval API", description = "Pay-period-based timekeeping approval operations")
public class TimekeepingApprovalController {

    private final TimekeepingApprovalService timekeepingApprovalService;

    @GetMapping("/approvals/people")
    @Operation(
            operationId = "listTimekeepingApprovalPeople",
            summary = "List Employees With Timekeeping Entries",
            description = """
                    Lists the distinct employees that have at least one timekeeping entry, with display name and \
                    employee number.
                    Use this tool to build the person picker for pay-period approval; use listTimekeepingEntries \
                    instead for one person's entries in a period.
                    Preconditions: none; display names come from the person identity replica, which is eventually \
                    consistent.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when no timekeeping entries exist.
                    """)
    @ApiResponse(responseCode = "200", description = "List of employees")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEKEEPING_VIEW + "')")
    public ResponseEntity<List<ApprovalPersonDto>> listApprovalPeople() {
        return ResponseEntity.ok(timekeepingApprovalService.listApprovalPeople());
    }

    @GetMapping("/time-periods")
    @Operation(
            operationId = "listTimePeriods",
            summary = "List Pay Periods For Timekeeping Approval",
            description = """
                    Lists pay periods with their start and end dates and status, newest first when scoped to a \
                    tenant.
                    Use this tool to choose the period for approval screens; use listTimekeepingEntries instead for \
                    the entries inside a chosen period.
                    Preconditions: none; tenantId is an optional scope and omitting it returns all periods in \
                    repository order.
                    Required inputs: none; tenantId (UUID) is an optional query parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when no pay periods exist.
                    """)
    @ApiResponse(responseCode = "200", description = "List of time periods")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEKEEPING_VIEW + "')")
    public ResponseEntity<List<TimePeriodDto>> listTimePeriods(@RequestParam(required = false) UUID tenantId) {
        return ResponseEntity.ok(timekeepingApprovalService.listTimePeriods(tenantId));
    }

    @GetMapping("/timekeeping-entries")
    @Operation(
            operationId = "listTimekeepingEntries",
            summary = "List Timekeeping Entries For Person And Period",
            description = """
                    Lists a person's timekeeping entries whose sessions fall entirely within the given pay period's \
                    date range, evaluated in UTC.
                    Use this tool to review entries before deciding a period; use getTimePeriodApproval instead for \
                    just the status counts.
                    Preconditions: the time period must exist; sessions straddling the period boundary are excluded \
                    because both start and end must fall inside it.
                    Required inputs: personId (UUID) and timePeriodId (UUID) query parameters; there is no request \
                    body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the time period does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "List of timekeeping entries")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEKEEPING_VIEW + "')")
    public ResponseEntity<List<TimekeepingEntryDto>> listTimekeepingEntries(
            @RequestParam UUID personId, @RequestParam UUID timePeriodId) {
        return ResponseEntity.ok(timekeepingApprovalService.listTimekeepingEntries(personId, timePeriodId));
    }

    @GetMapping("/time-period-approvals")
    @Operation(operationId = "getTimePeriodApproval", summary = "Get Time Period Approval Summary", description = """
                    Summarises a person's timekeeping approval state for a pay period: total, pending, approved, and \
                    rejected counts plus an overall status.
                    Use this tool for a status badge or gate; use listTimekeepingEntries instead when the individual \
                    entries are needed.
                    Preconditions: the time period must exist; the overall status is PENDING_APPROVAL while any \
                    entry is pending, else REJECTED if any entry was rejected, else APPROVED.
                    Required inputs: personId (UUID) and timePeriodId (UUID) query parameters; there is no request \
                    body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the time period does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Approval summary")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEKEEPING_VIEW + "')")
    public ResponseEntity<TimePeriodApprovalDto> getTimePeriodApproval(
            @RequestParam UUID personId, @RequestParam UUID timePeriodId) {
        return ResponseEntity.ok(timekeepingApprovalService.getTimePeriodApproval(personId, timePeriodId));
    }

    @PostMapping("/time-periods/{timePeriodId}/people/{personId}/approve")
    @Operation(operationId = "approveTimePeriod", summary = "Approve All Pending Entries In Period", description = """
                    Bulk-approves every PENDING_APPROVAL timekeeping entry for a person within a pay period's date \
                    range.
                    Use this tool for pay-period sign-off; do not use approveTimeEntriesBatch, which decides \
                    individually selected time entries instead of a whole period.
                    Preconditions: the time period must exist; only entries currently in PENDING_APPROVAL are \
                    touched, so already decided entries are left as they are.
                    Required inputs: timePeriodId (UUID) and personId (UUID) path parameters; there is no request \
                    body.
                    No events are emitted; entries are flipped to APPROVED in place and the response reports the \
                    processed count.
                    Returns 200 with processedCount 0 when nothing was pending, and 404 when the time period does \
                    not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Entries approved")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:approve"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEKEEPING_APPROVE + "')")
    public ResponseEntity<TimePeriodDecisionResponse> approvePeriod(
            @PathVariable UUID timePeriodId, @PathVariable UUID personId) {
        return ResponseEntity.ok(timekeepingApprovalService.approvePeriod(timePeriodId, personId));
    }

    @PostMapping("/time-periods/{timePeriodId}/people/{personId}/reject")
    @Operation(operationId = "rejectTimePeriod", summary = "Reject All Pending Entries In Period", description = """
                    Bulk-rejects every PENDING_APPROVAL timekeeping entry for a person within a pay period, storing \
                    the reason on each entry as its correctionReason.
                    Use this tool to send a whole period back for correction; do not use rejectTimeEntriesBatch, \
                    which rejects individually selected time entries.
                    Preconditions: the time period must exist; only entries currently in PENDING_APPROVAL are \
                    touched.
                    Required inputs: timePeriodId (UUID) and personId (UUID) path parameters and a body with a \
                    non-blank reason.
                    No events are emitted; entries are flipped to REJECTED in place and the response reports the \
                    processed count.
                    Returns 400 when reason is blank, and 404 when the time period does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Entries rejected")
    @ApiResponse(responseCode = "400", description = "Reason is required")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:reject"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEKEEPING_REJECT + "')")
    public ResponseEntity<TimePeriodDecisionResponse> rejectPeriod(
            @PathVariable UUID timePeriodId,
            @PathVariable UUID personId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rejection justification applied as the correctionReason on every pending"
                                    + " entry in the period.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Missing breaks", value = """
                                                                    {"reason":"Missing break entries for two shifts"}
                                                                    """)))
                    @RequestBody
                    @Valid
                    RejectTimePeriodRequest request) {
        return ResponseEntity.ok(timekeepingApprovalService.rejectPeriod(timePeriodId, personId, request.getReason()));
    }
}
