package com.positivity.workorder.controller;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Estimate API", description = "Endpoints for estimate management and approval workflow")
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
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
    @GetMapping("/{id}")
    public ResponseEntity<Estimate> getEstimateById(
            @Parameter(description = "ID of the estimate to retrieve", example = "1") @PathVariable Long id) {
        return estimateService.getEstimateById(id)
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
    @GetMapping("/shop/{shopId}")
    public List<Estimate> getEstimatesByShop(
            @Parameter(description = "ID of the shop", example = "1") @PathVariable Long shopId) {
        return estimateService.getEstimatesByShop(shopId);
    }

    @Operation(
        summary = "Create a new draft estimate", 
        description = "Create a new estimate in DRAFT status for a specific customer and vehicle. " +
                     "Validates that both customer and vehicle exist before creation. " +
                     "Returns the newly created estimate with a unique estimate number."
    )
    @ApiResponse(responseCode = "201", description = "Estimate created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request - validation failed.")
    @ApiResponse(responseCode = "403", description = "User does not have permission to create estimates.")
    @ApiResponse(responseCode = "404", description = "Customer or Vehicle not found.")
    @ApiResponse(responseCode = "500", description = "Internal server error.")
    @PostMapping("/draft")
    public ResponseEntity<CreateEstimateResponse> createDraftEstimate(
            @Parameter(description = "Request containing customer and vehicle IDs") 
            @Valid @RequestBody CreateEstimateRequest request,
            @Parameter(description = "ID of the user creating the estimate", example = "789")
            @RequestHeader(value = "X-User-Id", required = true) Long createdByUserId) {
        try {
            Estimate estimate = estimateService.createDraftEstimate(request, createdByUserId);
            CreateEstimateResponse response = CreateEstimateResponse.fromEntity(estimate);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            // Customer or Vehicle not found
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            // Internal server error
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Operation(summary = "Create a new estimate", description = "Add a new estimate to the system.")
    @ApiResponse(responseCode = "200", description = "Estimate created successfully.")
    @PostMapping
    public ResponseEntity<Estimate> createEstimate(
            @Parameter(description = "Estimate object to be created") @RequestBody Estimate estimate) {
        Estimate created = estimateService.createEstimate(estimate);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Approve an estimate", 
               description = "Transition estimate to approved state. Estimate must be in DRAFT status.")
    @ApiResponse(responseCode = "200", description = "Estimate approved successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be approved in current state.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{id}/approve")
    public ResponseEntity<Estimate> approveEstimate(
            @Parameter(description = "ID of the estimate to approve", example = "1") @PathVariable Long id,
            @Parameter(description = "ID of the user approving", example = "1") @RequestParam Long approvedBy) {
        try {
            Estimate approved = estimateService.approveEstimate(id, approvedBy);
            return ResponseEntity.ok(approved);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Decline an estimate", 
               description = "Transition estimate to declined state. Estimate can be declined from DRAFT or APPROVED status.")
    @ApiResponse(responseCode = "200", description = "Estimate declined successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be declined in current state.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{id}/decline")
    public ResponseEntity<Estimate> declineEstimate(
            @Parameter(description = "ID of the estimate to decline", example = "1") @PathVariable Long id,
            @Parameter(description = "Reason for decline") @RequestParam(required = false) String reason) {
        try {
            Estimate declined = estimateService.declineEstimate(id, reason);
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
    @PostMapping("/{id}/reopen")
    public ResponseEntity<Estimate> reopenEstimate(
            @Parameter(description = "ID of the estimate to reopen", example = "1") @PathVariable Long id) {
        try {
            Estimate reopened = estimateService.reopenEstimate(id);
            return ResponseEntity.ok(reopened);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete an estimate", description = "Delete an estimate by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Estimate deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEstimate(
            @Parameter(description = "ID of the estimate to delete", example = "1") @PathVariable Long id) {
        estimateService.deleteEstimate(id);
        return ResponseEntity.noContent().build();
    }
}
