package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.internal.entity.TimeEntryAdjustment;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.service.TimeEntryAdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/v1/people/timeEntries")
@Tag(name = "People TimeEntries", description = "Time entry adjustments and related APIs")
public class TimeEntryAdjustmentController {

    private final TimeEntryAdjustmentRepository adjustmentRepository;
    private final TimeEntryAdjustmentService adjustmentService;
    private final com.positivity.people.internal.repository.TimeEntryRepository timeEntryRepository;

    public TimeEntryAdjustmentController(TimeEntryAdjustmentRepository adjustmentRepository,
            TimeEntryAdjustmentService adjustmentService,
            com.positivity.people.internal.repository.TimeEntryRepository timeEntryRepository) {
        this.adjustmentRepository = adjustmentRepository;
        this.adjustmentService = adjustmentService;
        this.timeEntryRepository = timeEntryRepository;
    }

    @Operation(summary = "Create a time entry adjustment", description = "Submit a request to adjust a time entry. The adjustment will be in PENDING status until approved.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Adjustment created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @EmitEvent(id = "PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE", apiVersion = "1")
    @PostMapping(value = "/adjustments", consumes = "application/json", produces = "application/json")
    public ResponseEntity<TimeEntryAdjustmentResponse> createAdjustment(@RequestBody TimeEntryAdjustmentRequest req) {
        // Validate reasonCode is required
        if (req.getReasonCode() == null || req.getReasonCode().isBlank()) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                    "VALIDATION_FAILED",
                    "reasonCode is required", null);
            return ResponseEntity.badRequest().body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
        }

        // Validate time entry exists and is in PENDING_APPROVAL status
        if (req.getTimeEntryId() == null || req.getTimeEntryId().isBlank()) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                    "VALIDATION_FAILED",
                    "timeEntryId is required", null);
            return ResponseEntity.badRequest().body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
        }
        try {
            java.util.Optional<com.positivity.people.internal.entity.TimeEntry> entryOpt = timeEntryRepository
                    .findById(UUID.fromString(req.getTimeEntryId()));
            if (entryOpt.isEmpty()) {
                com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                        "NOT_FOUND",
                        "Time entry not found", null);
                return ResponseEntity.status(404).body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
            }
            com.positivity.people.internal.entity.TimeEntry entry = entryOpt.get();
            if (entry.getStatus() != com.positivity.people.internal.model.TimeEntryStatus.PENDING_APPROVAL) {
                com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                        "INVALID_STATE",
                        "Adjustments can only be created for entries in PENDING_APPROVAL status", null);
                return ResponseEntity.status(422).body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
            }
        } catch (Exception e) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                    "INTERNAL_ERROR",
                    "Error validating time entry", null);
            return ResponseEntity.status(500).body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
        }

        // Validate adjustment "one-of" rule: either proposedStartAt+proposedEndAt OR
        // minutesDelta (exclusive)
        boolean hasProposedTimes = req.getProposedStartAt() != null || req.getProposedEndAt() != null;
        boolean hasMinutesDelta = req.getMinutesDelta() != null;
        if (!(hasProposedTimes ^ hasMinutesDelta)) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                    "INVALID_REQUEST",
                    "Provide either both proposedStartAt and proposedEndAt, OR minutesDelta (exactly one)", null);
            return ResponseEntity.badRequest().body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
        }

        if (hasProposedTimes && (req.getProposedStartAt() == null || req.getProposedEndAt() == null)) {
            com.positivity.people.internal.dto.ErrorResponse err = new com.positivity.people.internal.dto.ErrorResponse(
                    "INVALID_REQUEST",
                    "Both proposedStartAt and proposedEndAt must be provided together", null);
            return ResponseEntity.badRequest().body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
        }

        TimeEntryAdjustment a = new TimeEntryAdjustment();
        a.setTimeEntryId(req.getTimeEntryId());
        a.setReasonCode(req.getReasonCode());
        a.setNotes(req.getNotes());
        if (req.getProposedStartAt() != null) {
            a.setProposedStartAt(req.getProposedStartAt().toInstant());
        }
        if (req.getProposedEndAt() != null) {
            a.setProposedEndAt(req.getProposedEndAt().toInstant());
        }
        a.setMinutesDelta(req.getMinutesDelta());
        a.setStatus(com.positivity.people.internal.model.AdjustmentStatus.PENDING);
        a.setCreatedBy(req.getCreatedBy());
        a.setCreatedAt(java.time.Instant.now());

        TimeEntryAdjustment saved = adjustmentRepository.save(a);
        TimeEntryAdjustmentResponse resp = new TimeEntryAdjustmentResponse(saved.getAdjustmentId(), true, "created");
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "List adjustments for a time entry", description = "Retrieve all adjustments associated with a specific time entry.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List returned")
    })
    @GetMapping(value = "/{timeEntryId}/adjustments", produces = "application/json")
    public ResponseEntity<List<TimeEntryAdjustment>> listForTimeEntry(@PathVariable String timeEntryId) {
        List<TimeEntryAdjustment> list = adjustmentRepository.findByTimeEntryId(timeEntryId);
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
    public ResponseEntity<?> approveAdjustment(@PathVariable java.util.UUID adjustmentId,
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
