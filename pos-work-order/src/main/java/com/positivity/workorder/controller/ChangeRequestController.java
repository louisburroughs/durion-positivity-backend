package com.positivity.workorder.controller;

import com.positivity.workorder.dto.ApproveChangeRequestDTO;
import com.positivity.workorder.dto.CreateChangeRequestDTO;
import com.positivity.workorder.dto.DeclineChangeRequestDTO;
import com.positivity.workorder.entity.ChangeRequest;
import com.positivity.workorder.service.ChangeRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Change Request API", description = "Endpoints for managing additional work requests and approvals")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChangeRequestController {
    private final ChangeRequestService changeRequestService;

    @Operation(
        summary = "Create a change request",
        description = "Technician creates a request for additional work beyond authorized scope. " +
                     "Items are marked as PENDING_APPROVAL until advisor approves. " +
                     "Requires description and at least one service or part item."
    )
    @ApiResponse(responseCode = "200", description = "Change request created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - missing description, no items, or validation failed")
    @ApiResponse(responseCode = "404", description = "Work order not found")
    @PostMapping("/work-orders/{workOrderId}/change-requests")
    public ResponseEntity<ChangeRequest> createChangeRequest(
            @Parameter(description = "ID of the work order", example = "1") 
            @PathVariable Long workOrderId,
            @Parameter(description = "Change request details including items") 
            @RequestBody CreateChangeRequestDTO dto) {
        try {
            dto.setWorkOrderId(workOrderId);
            ChangeRequest created = changeRequestService.createChangeRequest(dto);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
        summary = "Approve a change request",
        description = "Service Advisor approves the change request. " +
                     "Items move from PENDING_APPROVAL to READY_TO_EXECUTE status. " +
                     "Approval note is required as the approval artifact."
    )
    @ApiResponse(responseCode = "200", description = "Change request approved successfully")
    @ApiResponse(responseCode = "400", description = "Cannot approve - invalid state or missing approval note")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @PostMapping("/change-requests/{id}/approve")
    public ResponseEntity<ChangeRequest> approveChangeRequest(
            @Parameter(description = "ID of the change request", example = "1") 
            @PathVariable Long id,
            @Parameter(description = "Approval details including user ID and note") 
            @RequestBody ApproveChangeRequestDTO dto) {
        try {
            ChangeRequest approved = changeRequestService.approveChangeRequest(
                    id, dto.getApprovedBy(), dto.getApprovalNote());
            return ResponseEntity.ok(approved);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
        summary = "Decline a change request",
        description = "Service Advisor declines the change request. " +
                     "Items move from PENDING_APPROVAL to CANCELLED status (not billable). " +
                     "Approval note is required to record the decline decision."
    )
    @ApiResponse(responseCode = "200", description = "Change request declined successfully")
    @ApiResponse(responseCode = "400", description = "Cannot decline - invalid state or missing note")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @PostMapping("/change-requests/{id}/decline")
    public ResponseEntity<ChangeRequest> declineChangeRequest(
            @Parameter(description = "ID of the change request", example = "1") 
            @PathVariable Long id,
            @Parameter(description = "Decline details including note") 
            @RequestBody DeclineChangeRequestDTO dto) {
        try {
            ChangeRequest declined = changeRequestService.declineChangeRequest(id, dto.getApprovalNote());
            return ResponseEntity.ok(declined);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
        summary = "Record customer denial acknowledgment",
        description = "For declined emergency/safety items, record that customer acknowledged the denial. " +
                     "Required before closing the work order and returning the vehicle."
    )
    @ApiResponse(responseCode = "204", description = "Acknowledgment recorded successfully")
    @ApiResponse(responseCode = "400", description = "Not an emergency request or invalid state")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @PostMapping("/change-requests/{id}/acknowledge-denial")
    public ResponseEntity<Void> recordCustomerDenialAcknowledgment(
            @Parameter(description = "ID of the change request", example = "1") 
            @PathVariable Long id) {
        try {
            changeRequestService.recordCustomerDenialAcknowledgment(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
        summary = "Get change request by ID",
        description = "Retrieve details of a specific change request"
    )
    @ApiResponse(responseCode = "200", description = "Change request found")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @GetMapping("/change-requests/{id}")
    public ResponseEntity<ChangeRequest> getChangeRequestById(
            @Parameter(description = "ID of the change request", example = "1") 
            @PathVariable Long id) {
        try {
            ChangeRequest changeRequest = changeRequestService.getChangeRequestById(id);
            return ResponseEntity.ok(changeRequest);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
        summary = "Get all change requests for a work order",
        description = "Retrieve all change requests associated with a specific work order"
    )
    @ApiResponse(responseCode = "200", description = "List of change requests returned")
    @GetMapping("/work-orders/{workOrderId}/change-requests")
    public ResponseEntity<List<ChangeRequest>> getChangeRequestsByWorkOrder(
            @Parameter(description = "ID of the work order", example = "1") 
            @PathVariable Long workOrderId) {
        List<ChangeRequest> changeRequests = changeRequestService.getChangeRequestsByWorkOrder(workOrderId);
        return ResponseEntity.ok(changeRequests);
    }

    @Operation(
        summary = "Check if work order can be closed",
        description = "Verify all declined emergency/safety items have customer denial acknowledgment"
    )
    @ApiResponse(responseCode = "200", description = "Returns true if work order can be closed, false otherwise")
    @GetMapping("/work-orders/{workOrderId}/can-close")
    public ResponseEntity<Boolean> canCloseWorkOrder(
            @Parameter(description = "ID of the work order", example = "1") 
            @PathVariable Long workOrderId) {
        boolean canClose = changeRequestService.canCloseWorkOrder(workOrderId);
        return ResponseEntity.ok(canClose);
    }
}
