package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryDecisionBatchRequest;
import com.positivity.people.internal.dto.TimeEntryDecisionResponse;
import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import io.swagger.v3.oas.annotations.Operation;
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
            summary = "Batch approve time entries",
            description = "Approve multiple time entries. pos-people is authoritative for approval execution.")
    @ApiResponse(responseCode = "200", description = "Time entries approved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - decisions required")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_APPROVE", apiVersion = "1")
    @PostMapping("/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeEntry:approve"})
    @PreAuthorize("hasAuthority('people:timeEntry:approve')")
    public ResponseEntity<Object> approveTimeEntries(
            @RequestBody @Valid TimeEntryDecisionBatchRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        List<String> ids = request.getDecisions().stream()
                .map(TimeEntryDecisionBatchRequest.Decision::getTimeEntryId)
                .toList();

        List<TimeEntryDecisionResult> results = timeEntryService.approveEntries(ids, correlationId);

        TimeEntryDecisionResponse resp = new TimeEntryDecisionResponse(results);
        return ResponseEntity.ok(resp);
    }

    @Operation(
            summary = "Batch reject time entries",
            description = "Reject multiple time entries. rejectionReason is required for each decision.")
    @ApiResponse(responseCode = "200", description = "Time entries rejected successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - rejectionReason required for all decisions")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_REJECT", apiVersion = "1")
    @PostMapping("/reject")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timeEntry:reject"})
    @PreAuthorize("hasAuthority('people:timeEntry:reject')")
    public ResponseEntity<Object> rejectTimeEntries(
            @RequestBody @Valid TimeEntryDecisionBatchRequest request,
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
