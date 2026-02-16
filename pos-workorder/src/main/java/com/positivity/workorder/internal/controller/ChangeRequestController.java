package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.ApproveChangeRequestDTO;
import com.positivity.workorder.internal.dto.ChangeRequestResponse;
import com.positivity.workorder.internal.dto.CreateChangeRequestDTO;
import com.positivity.workorder.internal.dto.DeclineChangeRequestDTO;
import com.positivity.workorder.internal.dto.EmergencyOverrideDTO;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.service.ChangeRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Change Request API", description = "Endpoints for managing additional work requests and approvals")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
public class ChangeRequestController {
    private final ChangeRequestService changeRequestService;

    @Operation(summary = "Create a change request", description = "Technician creates a request for additional work beyond authorized scope. "
            +
            "Items are marked as PENDING_APPROVAL until advisor approves. " +
            "Requires description and at least one service or part item. " +
            "Supports idempotent creation via Idempotency-Key header to prevent duplicate change requests.")
    @ApiResponse(responseCode = "200", description = "Change request created successfully, or existing change request returned if idempotency key was previously processed")
    @ApiResponse(responseCode = "400", description = "Invalid request - missing description, no items, or validation failed")
    @ApiResponse(responseCode = "404", description = "Work order not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Change request details including items", required = true, content = @Content(schema = @Schema(implementation = CreateChangeRequestDTO.class), examples = @ExampleObject(name = "createChangeRequest", value = "{\"description\":\"Customer requested additional diagnostics\",\"approvalGated\":true}")))
    @PostMapping("/{workorderId}/changeRequests")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:change_request:create')")
    public ResponseEntity<ChangeRequestResponse> createChangeRequest(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID workorderId,
            @Parameter(description = "Change request details including items") @RequestBody CreateChangeRequestDTO dto,
            @Parameter(description = "Optional idempotency key to prevent duplicate creation (recommended for retries)", example = "change-request-create-550e8400-e29b-41d4-a716-446655440000") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            dto.setWorkorderId(workorderId);
            ChangeRequest created = changeRequestService.createChangeRequestWithIdempotency(dto, idempotencyKey);
            return ResponseEntity.ok(ChangeRequestResponse.fromEntity(created));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Approve a change request", description = "Service Advisor approves the change request. " +
            "Items move from PENDING_APPROVAL to READY_TO_EXECUTE status. " +
            "Approval note is required as the approval artifact.")
    @ApiResponse(responseCode = "200", description = "Change request approved successfully")
    @ApiResponse(responseCode = "400", description = "Cannot approve - invalid state or missing approval note")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Approval details including user ID and note", required = true, content = @Content(schema = @Schema(implementation = ApproveChangeRequestDTO.class), examples = @ExampleObject(name = "approveChangeRequest", value = "{\"approvedBy\":\"550e8400-e29b-41d4-a716-446655440100\",\"approvalNote\":\"Approved after customer confirmation\"}")))
    @PostMapping("/changeRequests/{changeId}/approve")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_APPROVE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:change_request:approve')")
    public ResponseEntity<ChangeRequestResponse> approveChangeRequest(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID changeId,
            @Parameter(description = "Approval details including user ID and note") @RequestBody ApproveChangeRequestDTO dto) {
        try {
            ChangeRequest approved = changeRequestService.approveChangeRequest(
                    changeId, dto.getApprovedBy(), dto.getApprovalNote());
            return ResponseEntity.ok(ChangeRequestResponse.fromEntity(approved));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Decline a change request", description = "Service Advisor declines the change request. " +
            "Items move from PENDING_APPROVAL to CANCELLED status (not billable). " +
            "Approval note is required to record the decline decision.")
    @ApiResponse(responseCode = "200", description = "Change request declined successfully")
    @ApiResponse(responseCode = "400", description = "Cannot decline - invalid state or missing note")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Decline details including note", required = true, content = @Content(schema = @Schema(implementation = DeclineChangeRequestDTO.class), examples = @ExampleObject(name = "declineChangeRequest", value = "{\"approvalNote\":\"Declined by customer\"}")))
    @PostMapping("/changeRequests/{changeId}/decline")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_DECLINE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:change_request:decline')")
    public ResponseEntity<ChangeRequestResponse> declineChangeRequest(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID changeId,
            @Parameter(description = "Decline details including note") @RequestBody DeclineChangeRequestDTO dto) {
        try {
            ChangeRequest declined = changeRequestService.declineChangeRequest(changeId, dto.getApprovalNote());
            return ResponseEntity.ok(ChangeRequestResponse.fromEntity(declined));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Record customer denial acknowledgment", description = "For declined emergency/safety items, record that customer acknowledged the denial. "
            +
            "Required before closing the work order and returning the vehicle.")
    @ApiResponse(responseCode = "204", description = "Acknowledgment recorded successfully")
    @ApiResponse(responseCode = "400", description = "Not an emergency request or invalid state")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @PostMapping("/changeRequests/{changeId}/acknowledgeDenial")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_DENIAL_ACKNOWLEDGE", apiVersion = "1")
    public ResponseEntity<Void> recordCustomerDenialAcknowledgment(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID changeId) {
        try {
            changeRequestService.recordCustomerDenialAcknowledgment(changeId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Apply emergency override", description = "Manager applies emergency override to approve a change request with exception. "
            +
            "Requires Manager role and a valid exception reason. " +
            "Items move from PENDING_APPROVAL to READY_TO_EXECUTE status.")
    @ApiResponse(responseCode = "200", description = "Emergency override applied successfully")
    @ApiResponse(responseCode = "400", description = "Cannot apply override - invalid state or missing reason")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions - Manager role required")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Emergency override details including manager ID and reason", required = true, content = @Content(schema = @Schema(implementation = EmergencyOverrideDTO.class), examples = @ExampleObject(name = "emergencyOverride", value = "{\"managerId\":\"550e8400-e29b-41d4-a716-446655440110\",\"exceptionReason\":\"Safety-critical repair authorized\"}")))
    @PostMapping("/changeRequests/{changeId}/emergency-override")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:change_request:emergency_override')")
    public ResponseEntity<ChangeRequestResponse> applyEmergencyOverride(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID changeId,
            @Parameter(description = "Emergency override details including manager ID and reason") @RequestBody EmergencyOverrideDTO dto) {
        try {
            ChangeRequest overridden = changeRequestService.applyEmergencyOverride(
                    changeId, dto.getManagerId(), dto.getExceptionReason());
            return ResponseEntity.ok(ChangeRequestResponse.fromEntity(overridden));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get change request by ID", description = "Retrieve details of a specific change request")
    @ApiResponse(responseCode = "200", description = "Change request found")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @GetMapping("/changeRequests/{changeId}")
    @PreAuthorize("hasAuthority('workorder:change_request:view')")
    public ResponseEntity<ChangeRequestResponse> getChangeRequestById(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID changeId) {
        try {
            ChangeRequest changeRequest = changeRequestService.getChangeRequestById(changeId);
            return ResponseEntity.ok(ChangeRequestResponse.fromEntity(changeRequest));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Get all change requests for a work order", description = "Retrieve all change requests associated with a specific work order")
    @ApiResponse(responseCode = "200", description = "List of change requests returned")
    @GetMapping("/{workorderId}/changeRequests")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:change_request:view')")
    public ResponseEntity<List<ChangeRequestResponse>> getChangeRequestsByWorkorder(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID workorderId) {
        List<ChangeRequest> changeRequests = changeRequestService.getChangeRequestsByWorkorder(workorderId);
        return ResponseEntity.ok(changeRequests.stream().map(ChangeRequestResponse::fromEntity).toList());
    }

    @Operation(summary = "Check if work order can be closed", description = "Verify all declined emergency/safety items have customer denial acknowledgment")
    @ApiResponse(responseCode = "200", description = "Returns true if work order can be closed, false otherwise")
    @GetMapping("/{workorderId}/canClose")
    public ResponseEntity<Boolean> canCloseWorkorder(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID workorderId) {
        boolean canClose = changeRequestService.canCloseWorkorder(workorderId);
        return ResponseEntity.ok(canClose);
    }
}
