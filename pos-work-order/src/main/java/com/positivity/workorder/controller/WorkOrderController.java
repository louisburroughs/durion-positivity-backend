package com.positivity.workorder.controller;

import com.positivity.workorder.entity.WorkOrder;
import com.positivity.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
}
