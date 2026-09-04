package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.ApproveChangeRequestDTO;
import com.positivity.workorder.internal.dto.ChangeRequestResponse;
import com.positivity.workorder.internal.dto.CreateChangeRequestDTO;
import com.positivity.workorder.internal.dto.DeclineChangeRequestDTO;
import com.positivity.workorder.internal.dto.EmergencyOverrideDTO;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.ChangeRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Change Request API", description = "Endpoints for managing additional work requests and approvals")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
public class ChangeRequestController {
    private final ChangeRequestService changeRequestService;

    @Operation(
            operationId = "createChangeRequest",
            summary = "Create Change Request for Additional Work",
            description = """
                    Creates a change request for work beyond the authorized scope, placing it in \
                    AWAITING_ADVISOR_REVIEW with its service and part items marked PENDING_APPROVAL.
                    Use this tool when a technician finds additional work mid-job; do not use \
                    approveChangeRequest or declineChangeRequest, which are the advisor's decision steps on an \
                    existing request.
                    Preconditions: the workorder must exist and be in WORK_IN_PROGRESS status, and emergency \
                    exception requests must carry the required emergency documentation on their items.
                    Required inputs: workorderId (UUID) as a path parameter, a non-blank description, and at \
                    least one entry in services or parts; an Idempotency-Key header is recommended — a repeated \
                    key returns the originally created request instead of a duplicate.
                    Emits a WORKORDER_CHANGE_REQUEST_CREATE event and renders a supplemental estimate PDF via \
                    the documents service when items are present.
                    Returns 400 when the description or items are missing, the workorder status is not \
                    WORK_IN_PROGRESS, or emergency documentation is incomplete, and 404 when the workorder does \
                    not exist.
                    """)
    @ApiResponse(
            responseCode = "200",
            description =
                    "Change request created successfully, or existing change request returned if idempotency key was previously processed")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request - missing description, no items, or validation failed")
    @ApiResponse(responseCode = "404", description = "Work order not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Requested additional work: description plus the service and part items needing approval.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = CreateChangeRequestDTO.class),
                            examples = @ExampleObject(name = "createChangeRequest", value = """
                                                    {"description":"Front brake pads worn to metal; replacement required",
                                                     "services":[{"serviceEntityId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a71","quantity":1}],
                                                     "parts":[{"productEntityId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a72","quantity":2}]}
                                                    """)))
    @PostMapping("/{workorderId}/changeRequests")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:change_request:create"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.CHANGE_REQUEST_CREATE + "')")
    public ResponseEntity<ChangeRequestResponse> createChangeRequest(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId,
            @Parameter(description = "Change request details including items") @RequestBody CreateChangeRequestDTO dto,
            @Parameter(
                            description =
                                    "Optional idempotency key to prevent duplicate creation (recommended for retries)",
                            example = "change-request-create-550e8400-e29b-41d4-a716-446655440000")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {
        try {
            dto.setWorkorderId(workorderId);
            var created = changeRequestService.createChangeRequestWithIdempotency(dto, idempotencyKey);
            return ResponseEntity.ok(created);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "approveChangeRequest", summary = "Approve a Change Request", description = """
                    Approves a change request awaiting advisor review, setting it to APPROVED, writing an \
                    immutable approval record, and moving its items from PENDING_APPROVAL to READY_TO_EXECUTE.
                    Use this tool for a normal advisor approval; do not use applyChangeRequestEmergencyOverride, \
                    which is the manager's exception path producing APPROVED_WITH_EXCEPTION.
                    Preconditions: the change request must exist in AWAITING_ADVISOR_REVIEW status, and the \
                    caller must be an authenticated user whose identity becomes the approver of record.
                    Required inputs: changeId (UUID) as a path parameter and a non-blank approvalNote as the \
                    approval artifact; the approvedBy body field is ignored in favor of the security context.
                    Emits a WORKORDER_CHANGE_REQUEST_APPROVE event and persists an ApprovalRecord audit row.
                    Returns 400 when the change request is not awaiting review or the note is missing, and 404 \
                    when the change request does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Change request approved successfully")
    @ApiResponse(responseCode = "400", description = "Cannot approve - invalid state or missing approval note")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Advisor's approval note recorded as the approval artifact.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ApproveChangeRequestDTO.class),
                            examples =
                                    @ExampleObject(
                                            name = "approveChangeRequest",
                                            value =
                                                    "{\"approvedBy\":\"550e8400-e29b-41d4-a716-446655440100\",\"approvalNote\":\"Approved after customer confirmation\"}")))
    @PostMapping("/changeRequests/{changeId}/approve")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_APPROVE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:change_request:approve"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.CHANGE_REQUEST_APPROVE + "')")
    public ResponseEntity<ChangeRequestResponse> approveChangeRequest(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID changeId,
            @Parameter(description = "Approval details including user ID and note") @RequestBody
                    ApproveChangeRequestDTO dto) {
        try {
            var approved =
                    changeRequestService.approveChangeRequest(changeId, dto.getApprovedBy(), dto.getApprovalNote());
            return ResponseEntity.ok(approved);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "declineChangeRequest", summary = "Decline a Change Request", description = """
                    Declines a change request awaiting advisor review, setting it to DECLINED, writing an \
                    immutable approval record, and moving its items from PENDING_APPROVAL to CANCELLED so they \
                    are not billable.
                    Use this tool when the customer refuses the additional work; do not use \
                    approveChangeRequest, which authorizes the work instead.
                    Preconditions: the change request must exist in AWAITING_ADVISOR_REVIEW status, and the \
                    caller must be an authenticated user whose identity becomes the decliner of record.
                    Required inputs: changeId (UUID) as a path parameter and a non-blank approvalNote recording \
                    the decline decision.
                    Emits a WORKORDER_CHANGE_REQUEST_DECLINE event; declined emergency items later require \
                    acknowledgeChangeRequestDenial before the workorder can close.
                    Returns 400 when the change request is not awaiting review or the note is missing, and 404 \
                    when the change request does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Change request declined successfully")
    @ApiResponse(responseCode = "400", description = "Cannot decline - invalid state or missing note")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Advisor's note recording why the change request was declined.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = DeclineChangeRequestDTO.class),
                            examples =
                                    @ExampleObject(
                                            name = "declineChangeRequest",
                                            value = "{\"approvalNote\":\"Declined by customer\"}")))
    @PostMapping("/changeRequests/{changeId}/decline")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_DECLINE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:change_request:decline"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.CHANGE_REQUEST_DECLINE + "')")
    public ResponseEntity<ChangeRequestResponse> declineChangeRequest(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID changeId,
            @Parameter(description = "Decline details including note") @RequestBody DeclineChangeRequestDTO dto) {
        try {
            var declined = changeRequestService.declineChangeRequest(changeId, dto.getApprovalNote());
            return ResponseEntity.ok(declined);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
            operationId = "acknowledgeChangeRequestDenial",
            summary = "Record Customer Denial Acknowledgment",
            description = """
                    Records that the customer acknowledged the denial of a declined emergency or safety change \
                    request, marking every emergency-flagged service and part item on it as acknowledged.
                    Use this tool after a customer refuses safety-critical work so the vehicle can be released; \
                    use checkWorkorderCanClose instead to verify whether any acknowledgments are still outstanding.
                    Preconditions: the change request must exist, be flagged as an emergency or safety \
                    exception, and be in DECLINED status.
                    Required inputs: changeId (UUID) as a path parameter; there is no request body.
                    Emits a WORKORDER_CHANGE_REQUEST_DENIAL_ACKNOWLEDGE event.
                    Returns 204 on success, 400 when the change request is not an emergency exception or is \
                    not declined, and 404 when the change request does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "Acknowledgment recorded successfully")
    @ApiResponse(responseCode = "400", description = "Not an emergency request or invalid state")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @PostMapping("/changeRequests/{changeId}/acknowledgeDenial")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_DENIAL_ACKNOWLEDGE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> recordCustomerDenialAcknowledgment(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID changeId) {
        try {
            changeRequestService.recordCustomerDenialAcknowledgment(changeId);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(
            operationId = "applyChangeRequestEmergencyOverride",
            summary = "Apply Manager Emergency Override",
            description = """
                    Applies a manager's emergency override to a change request awaiting advisor review, setting \
                    it to APPROVED_WITH_EXCEPTION, flagging it as an emergency exception, and moving its items \
                    to READY_TO_EXECUTE.
                    Use this tool only when safety-critical work must proceed without the normal approval \
                    artifact; do not use approveChangeRequest, which is the standard advisor approval.
                    Preconditions: the change request must exist in AWAITING_ADVISOR_REVIEW status, and the \
                    caller must hold workorder:change_request:emergency_override — the caller's identity becomes \
                    the overriding manager of record.
                    Required inputs: changeId (UUID) as a path parameter and a non-blank exceptionReason; the \
                    managerId body field is ignored in favor of the security context.
                    Emits a WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE event and persists an ApprovalRecord \
                    with the APPROVED_WITH_EXCEPTION resolution.
                    Returns 400 when the change request is not awaiting review or the reason is missing, and \
                    404 when the change request does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Emergency override applied successfully")
    @ApiResponse(responseCode = "400", description = "Cannot apply override - invalid state or missing reason")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions - Manager role required")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Reason justifying the manager's emergency exception approval.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = EmergencyOverrideDTO.class),
                            examples =
                                    @ExampleObject(
                                            name = "emergencyOverride",
                                            value =
                                                    "{\"managerId\":\"550e8400-e29b-41d4-a716-446655440110\",\"exceptionReason\":\"Safety-critical repair authorized\"}")))
    @PostMapping("/changeRequests/{changeId}/emergency-override")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:change_request:emergency_override"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.CHANGE_REQUEST_EMERGENCY_OVERRIDE + "')")
    public ResponseEntity<ChangeRequestResponse> applyEmergencyOverride(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID changeId,
            @Parameter(description = "Emergency override details including manager ID and reason") @RequestBody
                    EmergencyOverrideDTO dto) {
        try {
            var overridden = changeRequestService.applyEmergencyOverride(changeId, dto.getExceptionReason());
            return ResponseEntity.ok(overridden);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "getChangeRequest", summary = "Get Change Request by Id", description = """
                    Returns one change request with its status, description, emergency flags, approval details, \
                    and item associations.
                    Use this tool when the change request id is known; use listChangeRequests instead to see \
                    every change request on a workorder.
                    Preconditions: the change request must exist.
                    Required inputs: changeId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no change request exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Change request found")
    @ApiResponse(responseCode = "404", description = "Change request not found")
    @GetMapping("/changeRequests/{changeId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:change_request:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.CHANGE_REQUEST_VIEW + "')")
    public ResponseEntity<ChangeRequestResponse> getChangeRequestById(
            @Parameter(description = "ID of the change request", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID changeId) {
        var changeRequest = changeRequestService.getChangeRequestById(changeId);
        return ResponseEntity.ok(changeRequest);
    }

    @Operation(operationId = "listChangeRequests", summary = "List Change Requests for Workorder", description = """
                    Returns every change request attached to a workorder, in all statuses from \
                    AWAITING_ADVISOR_REVIEW through APPROVED, DECLINED, CANCELLED, and APPROVED_WITH_EXCEPTION.
                    Use this tool when reviewing a workorder's additional-work history; use getChangeRequest \
                    instead for one request by id.
                    Preconditions: none — an unknown workorderId simply yields an empty list.
                    Required inputs: workorderId (UUID) as a path parameter.
                    Emits a WORKORDER_CHANGE_REQUEST_LIST audit event; no change request state changes — this is \
                    a read-only projection.
                    Returns 200 with the list, possibly empty; no 404 is produced for unknown workorders.
                    """)
    @ApiResponse(responseCode = "200", description = "List of change requests returned")
    @GetMapping("/{workorderId}/changeRequests")
    @EmitEvent(id = "WORKORDER_CHANGE_REQUEST_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:change_request:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.CHANGE_REQUEST_VIEW + "')")
    public ResponseEntity<List<ChangeRequestResponse>> getChangeRequestsByWorkorder(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        return ResponseEntity.ok(changeRequestService.getChangeRequestsByWorkorder(workorderId));
    }

    @Operation(operationId = "checkWorkorderCanClose", summary = "Check Workorder Close Eligibility", description = """
                    Checks whether a workorder can be closed with respect to declined emergency change requests, \
                    returning true only when every declined emergency or safety item has a customer denial \
                    acknowledgment.
                    Use this tool before completing a workorder that had declined safety work; use \
                    acknowledgeChangeRequestDenial to clear a blocking acknowledgment rather than re-polling this check.
                    Preconditions: none — a workorder with no declined emergency requests yields true.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only check.
                    Returns 200 with a bare boolean body; no 404 is produced for unknown workorders.
                    """)
    @ApiResponse(responseCode = "200", description = "Returns true if work order can be closed, false otherwise")
    @GetMapping("/{workorderId}/canClose")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Boolean> canCloseWorkorder(
            @Parameter(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID workorderId) {
        boolean canClose = changeRequestService.canCloseWorkorder(workorderId);
        return ResponseEntity.ok(canClose);
    }
}
