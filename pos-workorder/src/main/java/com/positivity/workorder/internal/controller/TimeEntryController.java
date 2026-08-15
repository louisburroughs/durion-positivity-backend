package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.RejectTimeEntryRequest;
import com.positivity.workorder.internal.dto.TimeEntryMapper;
import com.positivity.workorder.internal.dto.TimeEntryResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.service.TimeEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Time Entry API", description = "Endpoints for approving and rejecting submitted time entries")
@RestController
@RequestMapping("/v1/workorders/timeEntries")
@RequiredArgsConstructor
public class TimeEntryController {

    private final TimeEntryService timeEntryService;

    @PostMapping("/{timeEntryId}/approve")
    @EmitEvent(id = "WORKORDER_TIME_ENTRY_APPROVED", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:timeEntry:approve"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.TIMEENTRY_APPROVE + "')")
    @Operation(operationId = "approveTimeEntry", summary = "Approve a Submitted Time Entry", description = """
                    Approves a SUBMITTED time entry, stamping the approving user and decision timestamp so the \
                    hours can flow onward in the workorder timekeeping workflow.
                    Use this tool when a supervisor accepts a technician's submitted hours; do not use \
                    rejectTimeEntry, which marks the entry REJECTED with a reason instead.
                    Preconditions: the time entry must exist and be in SUBMITTED status; entries already \
                    APPROVED or REJECTED cannot be re-decided.
                    Required inputs: timeEntryId (UUID) as a path parameter; there is no request body, and the \
                    approver identity is taken from the security context.
                    Emits a WORKORDER_TIME_ENTRY_APPROVED event recording the decision.
                    Returns 404 when no time entry exists for the id, and 409 when the entry is not in \
                    SUBMITTED status.
                    """)
    public ResponseEntity<TimeEntryResponse> approveTimeEntry(@PathVariable UUID timeEntryId) {
        return ResponseEntity.ok(TimeEntryMapper.toResponse(timeEntryService.approveTimeEntry(timeEntryId)));
    }

    @PostMapping("/{timeEntryId}/reject")
    @EmitEvent(id = "WORKORDER_TIME_ENTRY_REJECTED", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:timeEntry:reject"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.TIMEENTRY_REJECT + "')")
    @Operation(operationId = "rejectTimeEntry", summary = "Reject a Submitted Time Entry", description = """
                    Rejects a SUBMITTED time entry, recording the rejection reason, the deciding user, and the \
                    decision timestamp for technician follow-up.
                    Use this tool when submitted hours are wrong and must be sent back; do not use \
                    approveTimeEntry, which accepts the entry as-is.
                    Preconditions: the time entry must exist and be in SUBMITTED status; entries already \
                    APPROVED or REJECTED cannot be re-decided.
                    Required inputs: timeEntryId (UUID) as a path parameter and a body with a non-blank \
                    rejectionReason; the deciding identity is taken from the security context.
                    Emits a WORKORDER_TIME_ENTRY_REJECTED event carrying the rejection reason.
                    Returns 404 when no time entry exists for the id, and 409 when the entry is not in \
                    SUBMITTED status.
                    """)
    public ResponseEntity<TimeEntryResponse> rejectTimeEntry(
            @PathVariable UUID timeEntryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rejection details explaining why the submitted hours are being sent back.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Rejection",
                                                            value = """
                                                                    {"rejectionReason":"Hours overlap an existing approved entry for the same day"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RejectTimeEntryRequest request) {
        return ResponseEntity.ok(TimeEntryMapper.toResponse(timeEntryService.rejectTimeEntry(timeEntryId, request)));
    }
}
