package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.ApproveWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderResponse;
import com.positivity.workorder.internal.dto.CompletionPreconditionsResponse;
import com.positivity.workorder.internal.dto.CreateWorkorderRequest;
import com.positivity.workorder.internal.dto.ReopenWorkorderRequest;
import com.positivity.workorder.internal.dto.ReopenWorkorderResponse;
import com.positivity.workorder.internal.dto.StartWorkorderRequest;
import com.positivity.workorder.internal.dto.StartWorkorderResponse;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.dto.WorkorderStateTransitionResponse;
import com.positivity.workorder.internal.dto.WorkorderSnapshotResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.entity.WorkorderSnapshot;
import com.positivity.workorder.service.WorkorderService;
import com.positivity.workorder.service.WorkorderStateMachine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Work Order API", description = "Endpoints for work order management")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
public class WorkorderController {
    private final WorkorderService workorderService;

    @Operation(summary = "Get all work orders", description = "Retrieve a list of all work orders.")
    @ApiResponse(responseCode = "200", description = "List of work orders returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:workorder:view')")
    public List<WorkorderResponse> getAllWorkorders() {
        return workorderService.getAllWorkorders()
                .stream()
                .map(WorkorderResponse::fromEntity)
                .toList();
    }

    @Operation(summary = "Get work order by ID", description = "Retrieve a work order by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Work order found and returned.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}")
    @PreAuthorize("hasAuthority('workorder:workorder:view')")
    public ResponseEntity<WorkorderResponse> getWorkorderById(
            @Parameter(description = "ID of the work order to retrieve", example = "1") @PathVariable UUID workorderId) {
        return workorderService.getWorkorderById(workorderId)
                .map(WorkorderResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new work order", description = "Add a new work order to the system. Supports idempotent creation via Idempotency-Key header to prevent duplicate workorders.")
    @ApiResponse(responseCode = "200", description = "Work order created successfully, or existing work order returned if idempotency key was previously processed.")
    @PostMapping
    @EmitEvent(id = "WORKORDER_CREATE", apiVersion = "1")
    public ResponseEntity<WorkorderResponse> createWorkorder(
            @Parameter(description = "Work order creation request") @Valid @RequestBody CreateWorkorderRequest request,
            @Parameter(description = "Optional idempotency key to prevent duplicate creation (recommended for retries)") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Service handles entity creation internally, including idempotency check
        Workorder created = workorderService.createWorkorderWithIdempotency(
                request.getEstimateId(),
                request.getCustomerId(),
                idempotencyKey);
        return ResponseEntity.ok(WorkorderResponse.fromEntity(created));
    }

    @Operation(summary = "Delete a work order", description = "Delete a work order by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Work order deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @DeleteMapping("/{workorderId}")
    @EmitEvent(id = "WORKORDER_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteWorkorder(
            @Parameter(description = "ID of the work order to delete", example = "1") @PathVariable UUID workorderId) {
        workorderService.deleteWorkorder(workorderId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Start a work order", description = "Start work on a work order, transitioning it to WORK_IN_PROGRESS status.")
    @ApiResponse(responseCode = "200", description = "Work order started successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or pending change requests.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/start")
    @EmitEvent(id = "WORKORDER_START", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:workorder:start')")
    public ResponseEntity<StartWorkorderResponse> startWorkorder(
            @Parameter(description = "ID of the work order to start", example = "1") @PathVariable UUID workorderId,
            @RequestBody StartWorkorderRequest request) {
        try {
            workorderService.startWorkorder(workorderId, request.getUserId(), request.getReason());

            StartWorkorderResponse response = StartWorkorderResponse.builder()
                    .workorderId(workorderId)
                    .currentStatus("WORK_IN_PROGRESS")
                    .transitionedAt(Instant.now())
                    .message("Work order started successfully")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(
                    StartWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        }
    }

    @Operation(summary = "Get transition history", description = "Retrieve the state transition history for a work order.")
    @ApiResponse(responseCode = "200", description = "Transition history returned successfully.")
    @GetMapping("/{workorderId}/transitions")
    public ResponseEntity<List<WorkorderStateTransitionResponse>> getTransitionHistory(
            @Parameter(description = "ID of the work order", example = "1") @PathVariable UUID workorderId) {
        List<WorkorderStateTransition> history = workorderService.getTransitionHistory(workorderId);
        return ResponseEntity.ok(history.stream()
                .map(WorkorderStateTransitionResponse::fromEntity)
                .toList());
    }

    @Operation(summary = "Get snapshot history", description = "Retrieve the snapshot history for a work order.")
    @ApiResponse(responseCode = "200", description = "Snapshot history returned successfully.")
    @GetMapping("/{workorderId}/snapshots")
    public ResponseEntity<List<WorkorderSnapshotResponse>> getSnapshotHistory(
            @Parameter(description = "ID of the work order", example = "1") @PathVariable UUID workorderId) {
        List<WorkorderSnapshot> history = workorderService.getSnapshotHistory(workorderId);
        return ResponseEntity.ok(history.stream()
                .map(WorkorderSnapshotResponse::fromEntity)
                .toList());
    }

    @Operation(summary = "Approve a work order with customer signature", description = "Transition work order to APPROVED status with customer signature capture. "
            +
            "Work order can be approved from DRAFT status. Requires customer ID validation " +
            "and signature data (base64-encoded image).")
    @ApiResponse(responseCode = "200", description = "Work order approved successfully with signature captured.")
    @ApiResponse(responseCode = "400", description = "Work order cannot be approved in current state or customer ID mismatch.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/approval")
    @EmitEvent(id = "WORKORDER_APPROVE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:workorder:approve')")
    public ResponseEntity<WorkorderResponse> approveWorkorder(
            @Parameter(description = "ID of the work order to approve", example = "1") @PathVariable UUID workorderId,
            @Parameter(description = "Approval request with customer ID and signature capture") @Valid @RequestBody ApproveWorkorderRequest request) {
        try {
            Workorder approved = workorderService.approveWorkorder(
                    workorderId,
                    request.getCustomerId(),
                    request.getSignatureData(),
                    request.getSignatureMimeType(),
                    request.getSignerName(),
                    request.getNotes());
            return ResponseEntity.ok(WorkorderResponse.fromEntity(approved));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Complete a work order", description = "Complete a work order, transitioning it to COMPLETED status and emitting a WorkCompleted event.")
    @ApiResponse(responseCode = "200", description = "Work order completed successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or work order already completed.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/complete")
    @EmitEvent(id = "WORKORDER_COMPLETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:workorder:complete')")
    public ResponseEntity<CompleteWorkorderResponse> completeWorkorder(
            @Parameter(description = "ID of the work order to complete", example = "1") @PathVariable UUID workorderId,
            @RequestBody CompleteWorkorderRequest request) {
        try {
            String previousStatus = workorderService.getCurrentWorkorderStatus(workorderId);

            // Complete the work order (this will also emit the event)
            workorderService.completeWorkorder(workorderId, request.getUserId(), request.getCompletionNotes());

            CompleteWorkorderResponse response = CompleteWorkorderResponse.builder()
                    .workorderId(workorderId)
                    .previousStatus(previousStatus)
                    .currentStatus("COMPLETED")
                    .completedAt(workorderService.getCompletedAt(workorderId))
                    .message("Work order completed successfully")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(
                    CompleteWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Validate completion preconditions", description = "Evaluate completion preconditions for a workorder and return checklist + blocking reasons.")
    @ApiResponse(responseCode = "200", description = "Completion preconditions evaluated successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}/completion-preconditions")
    @PreAuthorize("hasAuthority('workorder:workorder:complete')")
    public ResponseEntity<CompletionPreconditionsResponse> getCompletionPreconditions(
            @Parameter(description = "ID of the workorder to validate", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID workorderId) {
        try {
            WorkorderStateMachine.CompletionPreconditions preconditions = workorderService
                    .getCompletionPreconditions(workorderId);

            CompletionPreconditionsResponse response = CompletionPreconditionsResponse.builder()
                    .workorderId(preconditions.workorderId())
                    .canComplete(preconditions.canComplete())
                    .currentStatus(preconditions.currentStatus())
                    .checklistItems(preconditions.checklistItems())
                    .blockingReasons(preconditions.blockingReasons())
                    .unresolvedApprovalGatedChangeRequests(preconditions.unresolvedApprovalGatedChangeRequests())
                    .nonTerminalServiceItems(preconditions.nonTerminalServiceItems())
                    .nonTerminalPartItems(preconditions.nonTerminalPartItems())
                    .emergencyDenialAcknowledged(preconditions.emergencyDenialAcknowledged())
                    .hasBillableItems(preconditions.hasBillableItems())
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Reopen completed workorder", description = "Controlled reopen for completed workorders. Requires elevated permission and mandatory reason.")
    @ApiResponse(responseCode = "200", description = "Workorder reopened successfully.")
    @ApiResponse(responseCode = "400", description = "Workorder cannot be reopened or reason missing.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/reopen")
    @EmitEvent(id = "WORKORDER_REOPEN", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:workorder:reopen_completed')")
    public ResponseEntity<ReopenWorkorderResponse> reopenWorkorder(
            @Parameter(description = "ID of the completed workorder to reopen", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID workorderId,
            @RequestBody ReopenWorkorderRequest request) {
        try {
            WorkorderService.ReopenResult reopened = workorderService.reopenCompletedWorkorder(
                    workorderId,
                    request.getUserId(),
                    request.getReopenReason());

            ReopenWorkorderResponse response = ReopenWorkorderResponse.builder()
                    .workorderId(reopened.workorderId())
                    .currentStatus(reopened.currentStatus())
                    .isReopened(reopened.isReopened())
                    .reopenedAt(reopened.reopenedAt())
                    .message("Workorder reopened successfully")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(
                    ReopenWorkorderResponse.builder()
                            .workorderId(workorderId)
                            .message(e.getMessage())
                            .build());
        }
    }
}
