package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryAdjustment;
import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.service.TimeEntryAdjustmentService;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
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
            summary = "Create a time entry adjustment",
            description =
                    "Submit a request to adjust a time entry. The adjustment will be in PENDING status until approved.")
    @ApiResponse(responseCode = "201", description = "Adjustment created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Time entry not found")
    @ApiResponse(responseCode = "409", description = "Invalid time entry state for adjustment")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE", apiVersion = "1")
    @PostMapping(value = "/adjustments", consumes = "application/json", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeAdjustment:create')")
    public ResponseEntity<TimeEntryAdjustmentResponse> createAdjustment(
            @Valid @RequestBody TimeEntryAdjustmentRequest req) {
        TimeEntryAdjustmentResponse resp = adjustmentService.createAdjustment(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @Operation(
            summary = "List adjustments for a time entry",
            description = "Retrieve all adjustments associated with a specific time entry.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @GetMapping(value = "/{timeEntryId}/adjustments", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeAdjustment:view')")
    public ResponseEntity<List<TimeEntryAdjustment>> listForTimeEntry(@PathVariable UUID timeEntryId) {
        List<TimeEntryAdjustment> list = adjustmentService.listForTimeEntry(timeEntryId);
        return ResponseEntity.ok(list);
    }

    @Operation(
            summary = "Approve a time entry adjustment",
            description = "Approve a pending time entry adjustment. Requires approval permissions.")
    @ApiResponse(responseCode = "200", description = "Adjustment approved successfully")
    @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Adjustment not found")
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_APPROVE", apiVersion = "1")
    @PostMapping(value = "/adjustments/{adjustmentId}/approve", produces = "application/json")
    @PreAuthorize("hasAuthority('people:timeAdjustment:approve')")
    public ResponseEntity<Object> approveAdjustment(
            @PathVariable java.util.UUID adjustmentId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        adjustmentService.approveAdjustment(adjustmentId, actorId, correlationId);
        return ResponseEntity.ok().build();
    }
}
