package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryAdjustment;
import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.service.TimeEntryAdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/v1/people/timeEntries")
@Tag(name = "People TimeEntries", description = "Time entry adjustments and related APIs")
public class TimeEntryAdjustmentController {

    private final TimeEntryAdjustmentService adjustmentService;

    public TimeEntryAdjustmentController(TimeEntryAdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
    }

    @Operation(summary = "Create a time entry adjustment", description = "Submit a request to adjust a time entry. The adjustment will be in PENDING status until approved.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Adjustment created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE", apiVersion = "1")
    @PostMapping(value = "/adjustments", consumes = "application/json", produces = "application/json")
    public ResponseEntity<TimeEntryAdjustmentResponse> createAdjustment(@RequestBody TimeEntryAdjustmentRequest req) {
        TimeEntryAdjustmentResponse resp = adjustmentService.createAdjustment(req);
        if (!resp.isSuccess()) {
            return ResponseEntity.badRequest().body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "List adjustments for a time entry", description = "Retrieve all adjustments associated with a specific time entry.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List returned")
    })
    @GetMapping(value = "/{timeEntryId}/adjustments", produces = "application/json")
    public ResponseEntity<List<TimeEntryAdjustment>> listForTimeEntry(@PathVariable String timeEntryId) {
        List<TimeEntryAdjustment> list = adjustmentService.listForTimeEntry(timeEntryId);
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "Approve a time entry adjustment", description = "Approve a pending time entry adjustment. Requires approval permissions.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Adjustment approved successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Adjustment not found")
    })
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_APPROVE", apiVersion = "1")
    @PostMapping(value = "/adjustments/{adjustmentId}/approve", produces = "application/json")
    public ResponseEntity<Object> approveAdjustment(@PathVariable java.util.UUID adjustmentId,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        java.util.Set<String> perms = null;
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
            perms = java.util.Arrays.stream(permissionsHeader.split(",")).map(String::trim)
                    .collect(Collectors.toSet());
        }
        String actor = userId != null ? userId : "system";
        boolean ok = adjustmentService.approveAdjustment(adjustmentId, actor, perms, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                "FORBIDDEN",
                "forbidden or not found", correlationId);
        return ResponseEntity.status(403).body(err);
    }
}
