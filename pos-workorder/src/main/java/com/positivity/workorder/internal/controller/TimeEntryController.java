package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.RejectTimeEntryRequest;
import com.positivity.workorder.internal.dto.TimeEntryMapper;
import com.positivity.workorder.internal.dto.TimeEntryResponse;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/workorders/timeEntries")
@RequiredArgsConstructor
public class TimeEntryController {

    private final TimeEntryService timeEntryService;

    @PostMapping("/{timeEntryId}/approve")
    @EmitEvent(id = "WORKORDER_TIME_ENTRY_APPROVED", apiVersion = "1")
    @PreAuthorize("hasAuthority('TimeEntry:Approve')")
    @Operation(summary = "Approve a time entry in SUBMITTED state")
    public ResponseEntity<TimeEntryResponse> approveTimeEntry(@PathVariable UUID timeEntryId) {
        return ResponseEntity.ok(TimeEntryMapper.toResponse(timeEntryService.approveTimeEntry(timeEntryId)));
    }

    @PostMapping("/{timeEntryId}/reject")
    @EmitEvent(id = "WORKORDER_TIME_ENTRY_REJECTED", apiVersion = "1")
    @PreAuthorize("hasAuthority('TimeEntry:Reject')")
    @Operation(summary = "Reject a time entry in SUBMITTED state")
    public ResponseEntity<TimeEntryResponse> rejectTimeEntry(
            @PathVariable UUID timeEntryId, @Valid @RequestBody RejectTimeEntryRequest request) {
        return ResponseEntity.ok(TimeEntryMapper.toResponse(timeEntryService.rejectTimeEntry(timeEntryId, request)));
    }
}
