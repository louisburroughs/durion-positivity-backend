package com.positivity.workorder.controller;

import com.positivity.workorder.dto.StartWorkOrderRequest;
import com.positivity.workorder.dto.StartWorkOrderResponse;
import com.positivity.workorder.entity.WorkOrder;
import com.positivity.workorder.entity.WorkOrderStateTransition;
import com.positivity.workorder.entity.WorkOrderSnapshot;
import com.positivity.workorder.entity.WorkOrderStatus;
import com.positivity.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@Tag(name = "Work Order API", description = "Endpoints for work order management")
@RestController
@RequestMapping("/api/workorders")
@RequiredArgsConstructor
public class WorkOrderController {
    private final WorkOrderService workOrderService;

    @Operation(summary = "Get all work orders", description = "Retrieve a list of all work orders.")
    @ApiResponse(responseCode = "200", description = "List of work orders returned successfully.")
    @GetMapping
    public List<WorkOrder> getAllWorkOrders() {
        return workOrderService.getAllWorkOrders();
    }

    @Operation(summary = "Get work order by ID", description = "Retrieve a work order by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Work order found and returned.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @GetMapping("/{id}")
    public ResponseEntity<WorkOrder> getWorkOrderById(
            @Parameter(description = "ID of the work order to retrieve", example = "1") @PathVariable Long id) {
        return workOrderService.getWorkOrderById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new work order", description = "Add a new work order to the system.")
    @ApiResponse(responseCode = "200", description = "Work order created successfully.")
    @PostMapping
    public ResponseEntity<WorkOrder> createWorkOrder(
            @Parameter(description = "Work order object to be created") @RequestBody WorkOrder workOrder) {
        WorkOrder created = workOrderService.createWorkOrder(workOrder);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Delete a work order", description = "Delete a work order by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Work order deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkOrder(
            @Parameter(description = "ID of the work order to delete", example = "1") @PathVariable Long id) {
        workOrderService.deleteWorkOrder(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Start a work order", description = "Start work on a work order, transitioning it to WORK_IN_PROGRESS status.")
    @ApiResponse(responseCode = "200", description = "Work order started successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid state transition or pending change requests.")
    @ApiResponse(responseCode = "404", description = "Work order not found.")
    @PostMapping("/{id}/start")
    public ResponseEntity<StartWorkOrderResponse> startWorkOrder(
            @Parameter(description = "ID of the work order to start", example = "1") @PathVariable Long id,
            @RequestBody StartWorkOrderRequest request) {
        try {
            WorkOrder workOrder = workOrderService.getWorkOrderById(id)
                    .orElseThrow(() -> new IllegalArgumentException("WorkOrder not found: " + id));
            
            WorkOrderStatus previousStatus = workOrder.getStatus();
            
            workOrderService.startWorkOrder(id, request.getUserId(), request.getReason());
            
            StartWorkOrderResponse response = StartWorkOrderResponse.builder()
                    .workOrderId(id)
                    .previousStatus(previousStatus)
                    .currentStatus(WorkOrderStatus.WORK_IN_PROGRESS)
                    .transitionedAt(Instant.now())
                    .message("Work order started successfully")
                    .build();
            
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(
                    StartWorkOrderResponse.builder()
                            .workOrderId(id)
                            .message(e.getMessage())
                            .build()
            );
        }
    }

    @Operation(summary = "Get transition history", description = "Retrieve the state transition history for a work order.")
    @ApiResponse(responseCode = "200", description = "Transition history returned successfully.")
    @GetMapping("/{id}/transitions")
    public ResponseEntity<List<WorkOrderStateTransition>> getTransitionHistory(
            @Parameter(description = "ID of the work order", example = "1") @PathVariable Long id) {
        List<WorkOrderStateTransition> history = workOrderService.getTransitionHistory(id);
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "Get snapshot history", description = "Retrieve the snapshot history for a work order.")
    @ApiResponse(responseCode = "200", description = "Snapshot history returned successfully.")
    @GetMapping("/{id}/snapshots")
    public ResponseEntity<List<WorkOrderSnapshot>> getSnapshotHistory(
            @Parameter(description = "ID of the work order", example = "1") @PathVariable Long id) {
        List<WorkOrderSnapshot> history = workOrderService.getSnapshotHistory(id);
        return ResponseEntity.ok(history);
    }
}
