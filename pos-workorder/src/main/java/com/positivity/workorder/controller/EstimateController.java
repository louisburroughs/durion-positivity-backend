package com.positivity.workorder.controller;

import com.positivity.workorder.dto.ApproveEstimateRequest;
import com.positivity.workorder.dto.CreateEstimateRequest;
import com.positivity.workorder.dto.CreateEstimateResponse;
import com.positivity.workorder.entity.Estimate;
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
    public List<Estimate> getAllEstimates() {
        return estimateService.getAllEstimates();
    }

    @Operation(summary = "Get estimate by ID", description = "Retrieve an estimate by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Estimate found and returned.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @GetMapping("/{estimateId}")
    public ResponseEntity<Estimate> getEstimateById(
            @Parameter(description = "ID of the estimate to retrieve", example = "1") @PathVariable Long estimateId) {
        return estimateService.getEstimateById(estimateId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get estimates by customer", description = "Retrieve all estimates for a specific customer.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/customer/{customerId}")
    public List<Estimate> getEstimatesByCustomer(
            @Parameter(description = "ID of the customer", example = "1") @PathVariable Long customerId) {
        return estimateService.getEstimatesByCustomer(customerId);
    }

    @Operation(summary = "Get estimates by shop", description = "Retrieve all estimates for a specific shop.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/shop/{locationId}")
    public List<Estimate> getEstimatesByShop(
            @Parameter(description = "ID of the shop", example = "1") @PathVariable Long locationId) {
        return estimateService.getEstimatesByLocation(locationId);
    }
    
    @Operation(summary = "Get estimates by location", description = "Retrieve all estimates for a specific location.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/location/{locationId}")
    public List<Estimate> getEstimatesByLocation(
            @Parameter(description = "ID of the location", example = "1") @PathVariable Long locationId) {
        return estimateService.getEstimatesByLocation(locationId);
    }

    @Operation(
        summary = "Create a new draft estimate", 
        description = "Create a new estimate in DRAFT status for a customer and vehicle. " +
                     "Requires ESTIMATE_CREATE permission. System will generate a unique estimate number " +
                     "and apply default values for location, currency, and tax region if not provided."
    )
    @ApiResponse(responseCode = "200", description = "Estimate created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request - missing required fields.")
    @ApiResponse(responseCode = "403", description = "Forbidden - user does not have ESTIMATE_CREATE permission.")
    @ApiResponse(responseCode = "500", description = "Internal server error - estimate creation failed.")
    @PostMapping()
    public ResponseEntity<?> createEstimate(
            @Parameter(description = "Estimate creation request with customer and vehicle IDs")
            @Valid @RequestBody CreateEstimateRequest request,
            @Parameter(description = "ID of the user creating the estimate", example = "1")
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {
        
        try {
            log.info("Received create estimate request for customerId={}, vehicleId={}", 
                    request.getCustomerId(), request.getVehicleId());
            
            // TODO: Check permissions - verify user has ESTIMATE_CREATE permission
            // For now, we'll assume the user has permission
            
            Estimate estimate = estimateService.createEstimate(request, userId);
            CreateEstimateResponse response = CreateEstimateResponse.fromEntity(estimate);
            
            log.info("Estimate created successfully: id={}, number={}", 
                    estimate.getId(), estimate.getEstimateNumber());
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating estimate: {}", e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "Bad Request",
                            "message", e.getMessage()
                    ));
                    
        } catch (Exception e) {
            log.error("Unexpected error creating estimate", e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Internal Server Error",
                            "message", "An unexpected error occurred while creating the estimate"
                    ));
        }
    }

   
    @Operation(summary = "Decline an estimate", 
               description = "Transition estimate to declined state. Estimate can be declined from DRAFT or APPROVED status.")
    @ApiResponse(responseCode = "200", description = "Estimate declined successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be declined in current state.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/decline")
    public ResponseEntity<Estimate> declineEstimate(
            @Parameter(description = "ID of the estimate to decline", example = "1") @PathVariable Long estimateId,
            @Parameter(description = "Reason for decline") @RequestParam(required = false) String reason) {
        try {
            Estimate declined = estimateService.declineEstimate(estimateId, reason);
            return ResponseEntity.ok(declined);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Reopen a declined estimate", 
               description = "Transition a declined estimate back to DRAFT state. Can only be done within the configured expiry period.")
    @ApiResponse(responseCode = "200", description = "Estimate reopened successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be reopened (not declined or expired).")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/reopen")
    public ResponseEntity<Estimate> reopenEstimate(
            @Parameter(description = "ID of the estimate to reopen", example = "1") @PathVariable Long estimateId) {
        try {
            Estimate reopened = estimateService.reopenEstimate(estimateId);
            return ResponseEntity.ok(reopened);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Approve an estimate with customer signature", 
               description = "Transition estimate to approved state with customer signature capture. " +
                           "Estimate can be approved from DRAFT status. Requires customer ID validation " +
                           "and signature data (base64-encoded image).")
    @ApiResponse(responseCode = "200", description = "Estimate approved successfully with signature captured.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be approved in current state or customer ID mismatch.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/approval")
    public ResponseEntity<Estimate> approveEstimate(
            @Parameter(description = "ID of the estimate to approve", example = "1") @PathVariable Long estimateId,
            @Parameter(description = "Approval request with customer ID and signature capture")
            @Valid @RequestBody ApproveEstimateRequest request) {
        try {
            Estimate approved = estimateService.approveEstimate(
                estimateId, 
                request.getCustomerId(),
                request.getSignatureData(),
                request.getSignatureMimeType(),
                request.getSignerName(),
                request.getNotes()
            );
            return ResponseEntity.ok(approved);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Failed to approve estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete an estimate", description = "Delete an estimate by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Estimate deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @DeleteMapping("/{estimateId}")
    public ResponseEntity<Void> deleteEstimate(
            @Parameter(description = "ID of the estimate to delete", example = "1") @PathVariable Long estimateId) {
        estimateService.deleteEstimate(estimateId);
        return ResponseEntity.noContent().build();
    }
}
