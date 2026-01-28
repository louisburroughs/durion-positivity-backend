package com.positivity.people.controller;

import com.positivity.people.dto.TimeEntryDecisionBatchRequest;
import com.positivity.people.dto.TimeEntryDecisionResponse;
import com.positivity.people.dto.TimeEntryDecisionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/v1/people/timeEntries")
@Tag(name = "Time Entry Approval API", description = "Approve/reject time entries (batch)")
public class TimeEntryApprovalController {

    private final com.positivity.people.service.TimeEntryService timeEntryService;

    public TimeEntryApprovalController(com.positivity.people.service.TimeEntryService timeEntryService) {
        this.timeEntryService = timeEntryService;
    }

    @Operation(summary = "Batch approve time entries", description = "Approve multiple time entries. pos-people is authoritative for approval execution.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Time entries approved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request - decisions required")
    })
    @PostMapping("/approve")
    public ResponseEntity<Object> approveTimeEntries(@RequestBody TimeEntryDecisionBatchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (request == null || request.getDecisions() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid request: decisions required");
        }

        // Prefer authentication from SecurityContext; fall back to headers
        String approver = (userId != null) ? userId : null;

        List<String> ids = request.getDecisions().stream().map(TimeEntryDecisionBatchRequest.Decision::getTimeEntryId)
                .toList();

        Set<String> perms = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            if (approver == null)
                approver = auth.getName();
            perms = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        }

        // Merge header-provided permissions if present
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
            Set<String> headerPerms = java.util.Arrays.stream(permissionsHeader.split(","))
                    .map(String::trim).collect(java.util.stream.Collectors.toSet());
            if (perms == null)
                perms = headerPerms;
            else {
                perms.addAll(headerPerms);
            }
        }

        if (approver == null)
            approver = "system";

        List<TimeEntryDecisionResult> results = timeEntryService.approveEntries(ids, approver, perms, correlationId);

        TimeEntryDecisionResponse resp = new TimeEntryDecisionResponse(results);
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "Batch reject time entries", description = "Reject multiple time entries. rejectionReason is required for each decision.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Time entries rejected successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request - rejectionReason required for all decisions")
    })
    @PostMapping("/reject")
    public ResponseEntity<Object> rejectTimeEntries(@RequestBody TimeEntryDecisionBatchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        if (request == null || request.getDecisions() == null || request.getDecisions().isEmpty()) {
            com.positivity.people.dto.ErrorResponse err = new com.positivity.people.dto.ErrorResponse("INVALID_REQUEST",
                    "Invalid request: decisions required and non-empty", correlationId);
            return ResponseEntity.badRequest().body(err);
        }

        // Validate that all decisions have rejectionReason
        for (TimeEntryDecisionBatchRequest.Decision decision : request.getDecisions()) {
            if (decision.getRejectionReason() == null || decision.getRejectionReason().isBlank()) {
                com.positivity.people.dto.ErrorResponse err = new com.positivity.people.dto.ErrorResponse(
                        "REJECTION_REASON_REQUIRED",
                        "rejectionReason is required for all decisions", correlationId);
                return ResponseEntity.badRequest().body(err);
            }
        }

        // Prefer authentication from SecurityContext; fall back to headers
        String rejector = (userId != null) ? userId : null;

        List<String> ids = request.getDecisions().stream().map(TimeEntryDecisionBatchRequest.Decision::getTimeEntryId)
                .toList();

        Set<String> perms = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            if (rejector == null)
                rejector = auth.getName();
            perms = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        }

        // Merge header-provided permissions if present
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
            Set<String> headerPerms = java.util.Arrays.stream(permissionsHeader.split(","))
                    .map(String::trim).collect(java.util.stream.Collectors.toSet());
            if (perms == null)
                perms = headerPerms;
            else {
                perms.addAll(headerPerms);
            }
        }

        if (rejector == null)
            rejector = "system";

        // Extract rejection reasons map and pass to service
        java.util.Map<String, String> rejectionReasons = new java.util.HashMap<>();
        for (TimeEntryDecisionBatchRequest.Decision d : request.getDecisions()) {
            rejectionReasons.put(d.getTimeEntryId(), d.getRejectionReason());
        }

        List<TimeEntryDecisionResult> results = timeEntryService.rejectEntries(ids, rejector, rejectionReasons, perms,
                correlationId);

        TimeEntryDecisionResponse resp = new TimeEntryDecisionResponse(results);
        return ResponseEntity.ok(resp);
    }
}
