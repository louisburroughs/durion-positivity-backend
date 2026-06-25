package com.positivity.people.internal.controller;

import com.positivity.people.internal.dto.ApprovalPersonDto;
import com.positivity.people.internal.dto.RejectTimePeriodRequest;
import com.positivity.people.internal.dto.TimePeriodApprovalDto;
import com.positivity.people.internal.dto.TimePeriodDecisionResponse;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimekeepingEntryDto;
import com.positivity.people.service.TimekeepingApprovalService;
import io.swagger.v3.oas.annotations.Operation;
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
            summary = "List employees with timekeeping entries",
            description = "Returns distinct employees who have at least one timekeeping entry.")
    @ApiResponse(responseCode = "200", description = "List of employees")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('people:timekeeping:view')")
    public ResponseEntity<List<ApprovalPersonDto>> listApprovalPeople() {
        return ResponseEntity.ok(timekeepingApprovalService.listApprovalPeople());
    }

    @GetMapping("/time-periods")
    @Operation(summary = "List time periods", description = "Returns all pay periods, optionally scoped to a tenant.")
    @ApiResponse(responseCode = "200", description = "List of time periods")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('people:timekeeping:view')")
    public ResponseEntity<List<TimePeriodDto>> listTimePeriods(@RequestParam(required = false) UUID tenantId) {
        return ResponseEntity.ok(timekeepingApprovalService.listTimePeriods(tenantId));
    }

    @GetMapping("/timekeeping-entries")
    @Operation(
            summary = "List timekeeping entries for a person in a period",
            description =
                    "Returns all timekeeping entries for the given person within the given time period's date range.")
    @ApiResponse(responseCode = "200", description = "List of timekeeping entries")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('people:timekeeping:view')")
    public ResponseEntity<List<TimekeepingEntryDto>> listTimekeepingEntries(
            @RequestParam UUID personId, @RequestParam UUID timePeriodId) {
        return ResponseEntity.ok(timekeepingApprovalService.listTimekeepingEntries(personId, timePeriodId));
    }

    @GetMapping("/time-period-approvals")
    @Operation(
            summary = "Get approval summary for a person in a period",
            description =
                    "Returns the approval status counts and overall status for the given person in the given period.")
    @ApiResponse(responseCode = "200", description = "Approval summary")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:view"})
    @PreAuthorize("hasAuthority('people:timekeeping:view')")
    public ResponseEntity<TimePeriodApprovalDto> getTimePeriodApproval(
            @RequestParam UUID personId, @RequestParam UUID timePeriodId) {
        return ResponseEntity.ok(timekeepingApprovalService.getTimePeriodApproval(personId, timePeriodId));
    }

    @PostMapping("/time-periods/{timePeriodId}/people/{personId}/approve")
    @Operation(
            summary = "Approve all pending timekeeping entries for a person in a period",
            description =
                    "Bulk-approves all PENDING_APPROVAL timekeeping entries for the given employee in the given period.")
    @ApiResponse(responseCode = "200", description = "Entries approved")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:approve"})
    @PreAuthorize("hasAuthority('people:timekeeping:approve')")
    public ResponseEntity<TimePeriodDecisionResponse> approvePeriod(
            @PathVariable UUID timePeriodId, @PathVariable UUID personId) {
        return ResponseEntity.ok(timekeepingApprovalService.approvePeriod(timePeriodId, personId));
    }

    @PostMapping("/time-periods/{timePeriodId}/people/{personId}/reject")
    @Operation(
            summary = "Reject all pending timekeeping entries for a person in a period",
            description =
                    "Bulk-rejects all PENDING_APPROVAL timekeeping entries for the given employee in the given period.")
    @ApiResponse(responseCode = "200", description = "Entries rejected")
    @ApiResponse(responseCode = "400", description = "Reason is required")
    @ApiResponse(responseCode = "404", description = "Time period not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timekeeping:reject"})
    @PreAuthorize("hasAuthority('people:timekeeping:reject')")
    public ResponseEntity<TimePeriodDecisionResponse> rejectPeriod(
            @PathVariable UUID timePeriodId,
            @PathVariable UUID personId,
            @RequestBody @Valid RejectTimePeriodRequest request) {
        return ResponseEntity.ok(timekeepingApprovalService.rejectPeriod(timePeriodId, personId, request.getReason()));
    }
}
