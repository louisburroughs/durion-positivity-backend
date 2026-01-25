package com.positivity.people.controller;

import com.positivity.people.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.entity.TimeEntryAdjustment;
import com.positivity.people.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.service.TimeEntryAdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/v1/people/timeEntries")
@Tag(name = "People - TimeEntries", description = "Time entry adjustments and related APIs")
public class TimeEntryAdjustmentController {

    private final TimeEntryAdjustmentRepository adjustmentRepository;
    private final TimeEntryAdjustmentService adjustmentService;

    public TimeEntryAdjustmentController(TimeEntryAdjustmentRepository adjustmentRepository,
            TimeEntryAdjustmentService adjustmentService) {
        this.adjustmentRepository = adjustmentRepository;
        this.adjustmentService = adjustmentService;
    }

    @Operation(summary = "Create a time entry adjustment")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Adjustment created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping(value = "/adjustments", consumes = "application/json", produces = "application/json")
    public ResponseEntity<TimeEntryAdjustmentResponse> createAdjustment(@RequestBody TimeEntryAdjustmentRequest req) {
        // Validate adjustment "one-of" rule: either proposedStartAt+proposedEndAt OR
        // minutesDelta (exclusive)
        boolean hasProposedTimes = req.getProposedStartAt() != null || req.getProposedEndAt() != null;
        boolean hasMinutesDelta = req.getMinutesDelta() != null;
        if (!(hasProposedTimes ^ hasMinutesDelta)) {
            com.positivity.people.dto.ErrorResponse err = new com.positivity.people.dto.ErrorResponse("INVALID_REQUEST",
                    "Provide either both proposedStartAt and proposedEndAt, OR minutesDelta (exactly one)", null);
            return ResponseEntity.badRequest().body(new TimeEntryAdjustmentResponse(null, false, err.getMessage()));
        }

        if (hasProposedTimes && (req.getProposedStartAt() == null || req.getProposedEndAt() == null)) {
            com.positivity.people.dto.ErrorResponse err = new com.positivity.people.dto.ErrorResponse("INVALID_REQUEST",
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
        a.setStatus(com.positivity.people.model.AdjustmentStatus.PENDING);
        a.setCreatedBy(req.getCreatedBy());
        a.setCreatedAt(java.time.Instant.now());

        TimeEntryAdjustment saved = adjustmentRepository.save(a);
        TimeEntryAdjustmentResponse resp = new TimeEntryAdjustmentResponse(saved.getAdjustmentId(), true, "created");
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "List adjustments for a time entry")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List returned")
    })
    @GetMapping(value = "/{timeEntryId}/adjustments", produces = "application/json")
    public ResponseEntity<List<TimeEntryAdjustment>> listForTimeEntry(@PathVariable String timeEntryId) {
        List<TimeEntryAdjustment> list = adjustmentRepository.findByTimeEntryId(timeEntryId);
        return ResponseEntity.ok(list);
    }

    @PostMapping(value = "/adjustments/{adjustmentId}/approve", produces = "application/json")
    public ResponseEntity<?> approveAdjustment(@PathVariable java.util.UUID adjustmentId,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        java.util.Set<String> perms = null;
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
            perms = java.util.Arrays.stream(permissionsHeader.split(",")).map(String::trim)
                    .collect(java.util.stream.Collectors.toSet());
        }
        String actor = userId != null ? userId : "system";
        boolean ok = adjustmentService.approveAdjustment(adjustmentId, actor, perms, correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.dto.ErrorResponse err = new com.positivity.people.dto.ErrorResponse("FORBIDDEN",
                "forbidden or not found", correlationId);
        return ResponseEntity.status(403).body(err);
    }
}
