package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.ApproveEstimateRequest;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.CreateEstimateResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.service.EstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Estimate API", description = "Endpoints for estimate management and approval workflow")
@RestController
@RequestMapping("/v1/workorders/estimates")
@RequiredArgsConstructor
@Slf4j
public class EstimateController {
    private final EstimateService estimateService;

    @Operation(summary = "Get all estimates", description = "Retrieve a list of all estimates.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_ESTIMATE_LIST", apiVersion = "1")
    public List<EstimateResponse> getAllEstimates() {
        return estimateService.getAllEstimates()
                .stream()
                .map(EstimateResponse::fromEntity)
                .toList();
    }

    @Operation(summary = "Get estimate by ID", description = "Retrieve an estimate by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Estimate found and returned.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @GetMapping("/{estimateId}")
    public ResponseEntity<EstimateResponse> getEstimateById(
            @Parameter(description = "ID of the estimate to retrieve", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        return estimateService.getEstimateById(estimateId)
                .map(EstimateResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get estimates by customer", description = "Retrieve all estimates for a specific customer.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/customer/{customerId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER", apiVersion = "1")
    public List<EstimateResponse> getEstimatesByCustomer(
            @Parameter(description = "ID of the customer", example = "1") @PathVariable UUID customerId) {
        return estimateService.getEstimatesByCustomer(customerId)
                .stream()
                .map(EstimateResponse::fromEntity)
                .toList();
    }

    @Operation(summary = "Get estimates by shop", description = "Retrieve all estimates for a specific shop.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/shop/{locationId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_SHOP", apiVersion = "1")
    public List<EstimateResponse> getEstimatesByShop(
            @Parameter(description = "ID of the shop", example = "1") @PathVariable UUID locationId) {
        return estimateService.getEstimatesByLocation(locationId)
                .stream()
                .map(EstimateResponse::fromEntity)
                .toList();
    }

    @Operation(summary = "Get estimates by location", description = "Retrieve all estimates for a specific location.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/location/{locationId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_LOCATION", apiVersion = "1")
    public List<EstimateResponse> getEstimatesByLocation(
            @Parameter(description = "ID of the location", example = "1") @PathVariable UUID locationId) {
        return estimateService.getEstimatesByLocation(locationId)
                .stream()
                .map(EstimateResponse::fromEntity)
                .toList();
    }

    @Operation(summary = "Create a new draft estimate", description = "Create a new estimate in DRAFT status for a customer and vehicle. "
            +
            "Requires ESTIMATE_CREATE permission. System will generate a unique estimate number " +
            "and apply default values for location, currency, and tax region if not provided.")
    @ApiResponse(responseCode = "200", description = "Estimate created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request - missing required fields.")
    @ApiResponse(responseCode = "403", description = "Forbidden - user does not have ESTIMATE_CREATE permission.")
    @ApiResponse(responseCode = "500", description = "Internal server error - estimate creation failed.")
    @PostMapping()
    @EmitEvent(id = "WORKORDER_ESTIMATE_CREATE", apiVersion = "1")
    public ResponseEntity<?> createEstimate(
            @Parameter(description = "Estimate creation request with customer and vehicle IDs") @Valid @RequestBody CreateEstimateRequest request,
            @Parameter(description = "ID of the user creating the estimate", example = "00000000-0000-0000-0000-000000000001") @RequestHeader(value = "X-User-Id", required = false, defaultValue = "00000000-0000-0000-0000-000000000001") UUID userId) {

        try {
            log.info("Received create estimate request for customerId={}, vehicleId={}",
                    request.getCustomerId(), request.getVehicleId());

            // TODO: Check permissions - verify user has ESTIMATE_CREATE permission
            // For now, we'll assume the user has permission

            Estimate estimate = estimateService.createEstimate(request, userId);
            EstimateResponse response = EstimateResponse.fromEntity(estimate);

            log.info("Estimate created successfully: id={}, number={}",
                    response.getId(), response.getEstimateNumber());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating estimate: {}", e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "Bad Request",
                            "message", e.getMessage()));

        } catch (Exception e) {
            log.error("Unexpected error creating estimate", e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Internal Server Error",
                            "message", "An unexpected error occurred while creating the estimate"));
        }
    }

    @Operation(summary = "Decline an estimate", description = "Transition estimate to declined state. Estimate can be declined from DRAFT or APPROVED status.")
    @ApiResponse(responseCode = "200", description = "Estimate declined successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be declined in current state.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/decline")
    @EmitEvent(id = "WORKORDER_ESTIMATE_DECLINE", apiVersion = "1")
    public ResponseEntity<EstimateResponse> declineEstimate(
            @Parameter(description = "ID of the estimate to decline", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Reason for decline") @RequestParam(required = false) String reason) {
        try {
            Estimate declined = estimateService.declineEstimate(estimateId, reason);
            return ResponseEntity.ok(EstimateResponse.fromEntity(declined));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Reopen a declined estimate", description = "Transition a declined estimate back to DRAFT state. Can only be done within the configured expiry period.")
    @ApiResponse(responseCode = "200", description = "Estimate reopened successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be reopened (not declined or expired).")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/reopen")
    @EmitEvent(id = "WORKORDER_ESTIMATE_REOPEN", apiVersion = "1")
    public ResponseEntity<EstimateResponse> reopenEstimate(
            @Parameter(description = "ID of the estimate to reopen", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        try {
            Estimate reopened = estimateService.reopenEstimate(estimateId);
            return ResponseEntity.ok(EstimateResponse.fromEntity(reopened));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Approve an estimate with customer signature", description = "Transition estimate to approved state with customer signature capture. "
            +
            "Estimate can be approved from DRAFT status. Requires customer ID validation " +
            "and signature data (base64-encoded image). For commercial accounts with PO enforcement " +
            "enabled, a purchase order number must be provided (CAP:092 Story #98).")
    @ApiResponse(responseCode = "200", description = "Estimate approved successfully with signature captured.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be approved in current state, customer ID mismatch, or PO required but not provided.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/approval")
    @EmitEvent(id = "WORKORDER_ESTIMATE_APPROVE", apiVersion = "1")
    public ResponseEntity<EstimateResponse> approveEstimate(
            @Parameter(description = "ID of the estimate to approve", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Approval request with customer ID and signature capture") @Valid @RequestBody ApproveEstimateRequest request) {
        try {
            Estimate approved = estimateService.approveEstimate(
                    estimateId,
                    request.getCustomerId(),
                    request.getSignatureData(),
                    request.getSignatureMimeType(),
                    request.getSignerName(),
                    request.getNotes(),
                    request.getPurchaseOrderNumber());
            return ResponseEntity.ok(EstimateResponse.fromEntity(approved));
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Failed to approve estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete an estimate", description = "Delete an estimate by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Estimate deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @DeleteMapping("/{estimateId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteEstimate(
            @Parameter(description = "ID of the estimate to delete", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        estimateService.deleteEstimate(estimateId);
        return ResponseEntity.noContent().build();
    }
}
