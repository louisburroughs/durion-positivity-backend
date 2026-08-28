package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryAdjustment;
import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.TimeEntryAdjustmentService;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people/timeEntries")
@Tag(name = "People TimeEntries", description = "Time entry adjustments and related APIs")
public class TimeEntryAdjustmentController {

    private final TimeEntryAdjustmentService adjustmentService;

    @Operation(
            operationId = "createTimeEntryAdjustment",
            summary = "Create A Pending Time Entry Adjustment",
            description = """
                    Creates a PENDING adjustment against a time entry, proposing either replacement start and end \
                    timestamps or a signed minutes delta.
                    Use this tool when a recorded time entry needs correction before approval; do not use \
                    approveTimeEntryAdjustment, which decides an already proposed adjustment.
                    Preconditions: the time entry must exist and be in PENDING_APPROVAL status; entries already \
                    approved or rejected cannot be adjusted.
                    Required inputs: timeEntryId (UUID), reasonCode, and exactly one of the pair proposedStartAt \
                    plus proposedEndAt (ISO-8601 offset timestamps) or minutesDelta (positive adds, negative \
                    subtracts); notes and createdBy are optional.
                    Emits a PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE event; the time entry itself is not modified until \
                    the adjustment is approved.
                    Returns 404 when the time entry does not exist, 409 when the entry is not in PENDING_APPROVAL \
                    status, and 400 when reasonCode is missing or the proposed-times versus minutesDelta rule is \
                    violated.
                    """)
    @ApiResponse(responseCode = "201", description = "Adjustment created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Time entry not found")
    @ApiResponse(responseCode = "409", description = "Invalid time entry state for adjustment")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE", apiVersion = "1")
    @PostMapping(value = "/adjustments", consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeAdjustment:create"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEADJUSTMENT_CREATE + "')")
    public ResponseEntity<TimeEntryAdjustmentResponse> createAdjustment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Proposed correction for one time entry: replacement start/end timestamps"
                                    + " or a signed minutes delta, with a reason code.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Subtract a missed break", value = """
                                                                    {"timeEntryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "reasonCode":"MISSED_BREAK",
                                                                     "notes":"Employee forgot to log lunch break",
                                                                     "minutesDelta":-30}
                                                                    """)))
                    @Valid
                    @RequestBody
                    TimeEntryAdjustmentRequest req) {
        TimeEntryAdjustmentResponse resp = adjustmentService.createAdjustment(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @Operation(
            operationId = "listTimeEntryAdjustments",
            summary = "List Adjustments For A Time Entry",
            description = """
                    Lists all adjustments recorded against one time entry, in any status.
                    Use this tool to review an entry's correction history; use createTimeEntryAdjustment instead to \
                    propose a new correction.
                    Preconditions: none; an unknown timeEntryId simply yields no rows.
                    Required inputs: timeEntryId (UUID) path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the time entry has no adjustments.
                    """)
    @ApiResponse(responseCode = "200", description = "List returned")
    @GetMapping(value = "/{timeEntryId}/adjustments", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeAdjustment:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEADJUSTMENT_VIEW + "')")
    public ResponseEntity<List<TimeEntryAdjustment>> listForTimeEntry(@PathVariable UUID timeEntryId) {
        List<TimeEntryAdjustment> list = adjustmentService.listForTimeEntry(timeEntryId);
        return ResponseEntity.ok(list);
    }

    @Operation(
            operationId = "approveTimeEntryAdjustment",
            summary = "Approve A Pending Time Entry Adjustment",
            description = """
                    Approves a time entry adjustment, stamping the deciding user and decision time and writing an \
                    ADJUSTMENT_APPROVED audit row.
                    Use this tool to accept a proposed correction; do not use approveTimeEntriesBatch, which \
                    approves the time entries themselves rather than adjustments.
                    Preconditions: the adjustment must exist; no status gate is enforced, so re-approving an already \
                    decided adjustment overwrites the previous decision.
                    Required inputs: adjustmentId (UUID) path parameter; an optional X-Correlation-Id header is \
                    carried into the audit trail.
                    Emits a PEOPLE_TIME_ENTRY_ADJUSTMENT_APPROVE event; the underlying time entry's own timestamps \
                    are not recalculated by this call.
                    Returns 404 when no adjustment exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Adjustment approved successfully")
    @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Adjustment not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_APPROVE", apiVersion = "1")
    @PostMapping(value = "/adjustments/{adjustmentId}/approve", produces = "application/json")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeAdjustment:approve"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEADJUSTMENT_APPROVE + "')")
    public ResponseEntity<Object> approveAdjustment(
            @PathVariable java.util.UUID adjustmentId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        adjustmentService.approveAdjustment(adjustmentId, actorId, correlationId);
        return ResponseEntity.ok().build();
    }
}
