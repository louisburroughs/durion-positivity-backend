package com.positivity.workorder.controller;

import com.positivity.workorder.dto.ApproveWorkorderRequest;
import com.positivity.workorder.dto.CompleteWorkorderRequest;
import com.positivity.workorder.dto.CompleteWorkorderResponse;
import com.positivity.workorder.dto.StartWorkorderRequest;
import com.positivity.workorder.dto.StartWorkorderResponse;
import com.positivity.workorder.entity.Workorder;
import com.positivity.workorder.entity.WorkorderStateTransition;
import com.positivity.workorder.entity.WorkorderSnapshot;
import com.positivity.workorder.entity.WorkorderStatus;
import com.positivity.workorder.service.WorkorderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@Tag(name = "Work Order API", description = "Endpoints for work order management")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
public class WorkorderController {
    private final WorkorderService workorderService;

    @Operation(summary = "Get all work orders", description = "Retrieve a list of all work orders.")
    @ApiResponse(responseCode = "200", description = "List of work orders returned successfully.")
    @GetMapping
    public List<Workorder> getAllWorkorders() {
        return workorderService.getAllWorkorders();
    }

    @Operation(summary = "Get work order by ID", description = "Retrieve a work order by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Work order found and returned.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{workorderId}")
    public ResponseEntity<Workorder> getWorkorderById(
            @Parameter(description = "ID of the work order to retrieve", example = "1") @PathVariable Long workorderId) {
        return workorderService.getWorkorderById(workorderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new work order", description = "Add a new work order to the system.")
    @ApiResponse(responseCode = "200", description = "Work order created successfully.")
    @PostMapping
    public ResponseEntity<Workorder> createWorkorder(
            @Parameter(description = "Work order object to be created") @RequestBody Workorder workorder) {
        Workorder created = workorderService.createWorkorder(workorder);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Delete a work order", description = "Delete a work order by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Work order deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @DeleteMapping("/{workorderId}")
    public ResponseEntity<Void> deleteWorkorder(
            @Parameter(description = "ID of the work order to delete", example = "1") @PathVariable Long workorderId) {
        workorderService.deleteWorkorder(workorderId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Start a work order", description = "Start work on a work order, transitioning it to WORK_IN_PROGRESS status.")
    @ApiResponse(responseCode = "200", description = "Work order started successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or pending change requests.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/start")
    public ResponseEntity<StartWorkorderResponse> startWorkorder(
            @Parameter(description = "ID of the work order to start", example = "1") @PathVariable Long workorderId,
            @RequestBody StartWorkorderRequest request) {
        try {
            Workorder workorder = workorderService.getWorkorderById(workorderId)
                    .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

            WorkorderStatus previousStatus = workorder.getStatus();

            workorderService.startWorkorder(workorderId, request.getUserId(), request.getReason());

            StartWorkorderResponse response = StartWorkorderResponse.builder()
                    .workorderId(workorderId)
                    .previousStatus(previousStatus)
                    .currentStatus(WorkorderStatus.WORK_IN_PROGRESS)
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
    public ResponseEntity<List<WorkorderStateTransition>> getTransitionHistory(
            @Parameter(description = "ID of the work order", example = "1") @PathVariable Long workorderId) {
        List<WorkorderStateTransition> history = workorderService.getTransitionHistory(workorderId);
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "Get snapshot history", description = "Retrieve the snapshot history for a work order.")
    @ApiResponse(responseCode = "200", description = "Snapshot history returned successfully.")
    @GetMapping("/{workorderId}/snapshots")
    public ResponseEntity<List<WorkorderSnapshot>> getSnapshotHistory(
            @Parameter(description = "ID of the work order", example = "1") @PathVariable Long workorderId) {
        List<WorkorderSnapshot> history = workorderService.getSnapshotHistory(workorderId);
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "Approve a work order with customer signature", description = "Transition work order to APPROVED status with customer signature capture. "
            +
            "Work order can be approved from DRAFT status. Requires customer ID validation " +
            "and signature data (base64-encoded image).")
    @ApiResponse(responseCode = "200", description = "Work order approved successfully with signature captured.")
    @ApiResponse(responseCode = "400", description = "Work order cannot be approved in current state or customer ID mismatch.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/approval")
    public ResponseEntity<Workorder> approveWorkorder(
            @Parameter(description = "ID of the work order to approve", example = "1") @PathVariable Long workorderId,
            @Parameter(description = "Approval request with customer ID and signature capture") @Valid @RequestBody ApproveWorkorderRequest request) {
        try {
            Workorder approved = workorderService.approveWorkorder(
                    workorderId,
                    request.getCustomerId(),
                    request.getSignatureData(),
                    request.getSignatureMimeType(),
                    request.getSignerName(),
                    request.getNotes());
            return ResponseEntity.ok(approved);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Complete a work order", description = "Complete a work order, transitioning it to COMPLETED status and emitting a WorkCompleted event.")
    @ApiResponse(responseCode = "200", description = "Work order completed successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or work order already completed.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{workorderId}/complete")
    public ResponseEntity<CompleteWorkorderResponse> completeWorkorder(
            @Parameter(description = "ID of the work order to complete", example = "1") @PathVariable Long workorderId,
            @RequestBody CompleteWorkorderRequest request) {
        try {
            Workorder workorder = workorderService.getWorkorderById(workorderId)
                    .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

            WorkorderStatus previousStatus = workorder.getStatus();

            // Complete the work order (this will also emit the event)
            workorderService.completeWorkorder(workorderId, request.getUserId(), request.getCompletionNotes());

            // Fetch the updated work order
            Workorder completedWorkorder = workorderService.getWorkorderById(workorderId)
                    .orElseThrow(
                            () -> new IllegalArgumentException("Workorder not found after completion: " + workorderId));

            CompleteWorkorderResponse response = CompleteWorkorderResponse.builder()
                    .workorderId(workorderId)
                    .previousStatus(previousStatus)
                    .currentStatus(WorkorderStatus.COMPLETED)
                    .completedAt(completedWorkorder.getCompletedAt())
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
}
