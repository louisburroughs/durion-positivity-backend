package com.positivity.workorder.internal.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityNotFoundException;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.dto.ApproveEstimateRequest;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateItemResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSnapshotResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.UpdateEstimateItemRequest;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.service.EstimateService;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkorderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Tag(name = "Estimate API", description = "Endpoints for estimate management and approval workflow")
@RestController
@RequestMapping("/v1/workorders/estimates")
@RequiredArgsConstructor
@Slf4j
public class EstimateController {
    private static final String SYSTEM = "SYSTEM";
    private final EstimateService estimateService;
    private final WorkorderService workorderService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "Get all estimates", description = "Retrieve a list of all estimates.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_ESTIMATE_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    public List<EstimateResponse> getAllEstimates() {
        return estimateService.getAllEstimates();
    }

    @Operation(summary = "Get estimate by ID", description = "Retrieve an estimate by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Estimate found and returned.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @GetMapping("/{estimateId}")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    public ResponseEntity<EstimateResponse> getEstimateById(
            @Parameter(description = "ID of the estimate to retrieve", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        return estimateService.getEstimateById(estimateId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get estimates by customer", description = "Retrieve all estimates for a specific customer.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/customer/{customerId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    public List<EstimateResponse> getEstimatesByCustomer(
            @Parameter(description = "ID of the customer", example = "550e8400-e29b-41d4-a716-446655440010") @PathVariable UUID customerId) {
        return estimateService.getEstimatesByCustomer(customerId);
    }

    @Operation(summary = "Get estimates by shop", description = "Retrieve all estimates for a specific shop.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/shop/{locationId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_SHOP", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    public List<EstimateResponse> getEstimatesByShop(
            @Parameter(description = "ID of the shop", example = "550e8400-e29b-41d4-a716-446655440020") @PathVariable UUID locationId) {
        return estimateService.getEstimatesByLocation(locationId);
    }

    @Operation(summary = "Get estimates by location", description = "Retrieve all estimates for a specific location.")
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/location/{locationId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_LOCATION", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    public List<EstimateResponse> getEstimatesByLocation(
            @Parameter(description = "ID of the location", example = "550e8400-e29b-41d4-a716-446655440020") @PathVariable UUID locationId) {
        return estimateService.getEstimatesByLocation(locationId);
    }

    @Operation(summary = "Create a new draft estimate", description = "Create a new estimate in DRAFT status for a customer and vehicle. "
            +
            "Requires ESTIMATE_CREATE permission. System will generate a unique estimate number " +
            "and apply default values for location, currency, and tax region if not provided.")
    @ApiResponse(responseCode = "201", description = "Estimate created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request - missing required fields.")
    @ApiResponse(responseCode = "403", description = "Forbidden - user does not have ESTIMATE_CREATE permission.")
    @ApiResponse(responseCode = "409", description = "Conflict - estimate could not be created due to state or integrity constraints.")
    @ApiResponse(responseCode = "500", description = "Internal server error - unexpected estimate creation failure.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Estimate creation request with customer and vehicle IDs", required = true, content = @Content(schema = @Schema(implementation = CreateEstimateRequest.class), examples = @ExampleObject(name = "createEstimate", value = "{\"customerId\":\"550e8400-e29b-41d4-a716-446655440010\",\"vehicleId\":\"550e8400-e29b-41d4-a716-446655440011\",\"locationId\":\"550e8400-e29b-41d4-a716-446655440020\"}")))
    @PostMapping()
    @EmitEvent(id = "WORKORDER_ESTIMATE_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:create')")
    public ResponseEntity<EstimateResponse> createEstimate(
            @Parameter(description = "Estimate creation request with customer and vehicle IDs") @Valid @RequestBody CreateEstimateRequest request) {

        try {
            log.info("Received create estimate request for customerId={}, vehicleId={}",
                    request.getCustomerId(), request.getVehicleId());

            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateResponse response = estimateService.createEstimate(request, username);

            log.info("Estimate created successfully: id={}, number={}",
                    response.getId(), response.getEstimateNumber());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating estimate: {}", e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (DataIntegrityViolationException e) {
            log.warn("Conflict creating estimate: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();

        } catch (Exception e) {
            log.error("Unexpected error creating estimate", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Operation(summary = "Decline an estimate", description = "Transition estimate to declined state. Estimate can be declined from DRAFT or APPROVED status.")
    @ApiResponse(responseCode = "200", description = "Estimate declined successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be declined in current state.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/decline")
    @EmitEvent(id = "WORKORDER_ESTIMATE_DECLINE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:decline')")
    public ResponseEntity<EstimateResponse> declineEstimate(
            @Parameter(description = "ID of the estimate to decline", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Reason for decline", example = "Customer declined additional work") @RequestParam(required = false) String reason) {
        try {
            EstimateResponse declined = estimateService.declineEstimate(estimateId, reason);
            return ResponseEntity.ok(declined);
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
    @PreAuthorize("hasAuthority('workorder:estimate:reopen')")
    public ResponseEntity<EstimateResponse> reopenEstimate(
            @Parameter(description = "ID of the estimate to reopen", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        try {
            EstimateResponse reopened = estimateService.reopenEstimate(estimateId);
            return ResponseEntity.ok(reopened);
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Approval request with customer ID, signature capture, and optional selective line item approvals", required = true, content = @Content(schema = @Schema(implementation = ApproveEstimateRequest.class), examples = @ExampleObject(name = "approveEstimate", value = "{\"customerId\":\"550e8400-e29b-41d4-a716-446655440010\",\"signatureData\":\"base64-signature\",\"signatureMimeType\":\"image/png\",\"signerName\":\"Jane Customer\",\"notes\":\"Approved estimate\",\"purchaseOrderNumber\":\"PO-12345\"}")))
    @PostMapping("/{estimateId}/approval")
    @EmitEvent(id = "WORKORDER_ESTIMATE_APPROVE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:approve')")
    public ResponseEntity<EstimateResponse> approveEstimate(
            @Parameter(description = "ID of the estimate to approve", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Approval request with customer ID, signature capture, and optional selective line item approvals") @Valid @RequestBody ApproveEstimateRequest request) {
        try {
            EstimateResponse approved = estimateService.approveEstimate(
                    estimateId,
                    request.getCustomerId(),
                    request.getSignatureData(),
                    request.getSignatureMimeType(),
                    request.getSignerName(),
                    request.getNotes(),
                    request.getPurchaseOrderNumber(),
                    request.getLineItemApprovals()); // CAP:003 - Pass selective line item approvals
            return ResponseEntity.ok(approved);
        } catch (EntityNotFoundException e) {
            log.warn("Estimate {} not found: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Failed to approve estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Promote approved estimate to workorder", description = "Promote an approved estimate to a workorder. "
            +
            "Validates preconditions: estimate must be APPROVED, not expired, have approved items, and not already promoted. "
            +
            "Returns 409 ALREADY_PROMOTED with existingWorkorderId if estimate was previously promoted (idempotency). "
            +
            "CAP:004 Story #26 - Create a workorder from approved estimate.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Workorder created successfully from estimate"),
            @ApiResponse(responseCode = "400", description = "Validation error - estimate not in correct state"),
            @ApiResponse(responseCode = "404", description = "Estimate not found"),
            @ApiResponse(responseCode = "409", description = "Estimate already promoted (ALREADY_PROMOTED) or approval invalid/expired")
    })
    @PostMapping("/{estimateId}/promote")
    @EmitEvent(id = "WORKORDER_ESTIMATE_PROMOTE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:promote')")
    public ResponseEntity<WorkorderResponse> promoteEstimateToWorkorder(
            @Parameter(description = "ID of the estimate to promote", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Idempotency-Key header for safe retries", example = "estimate-promote-550e8400-e29b-41d4-a716-446655440000") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            log.info("Promoting estimate {} to workorder (idempotencyKey={})", estimateId, idempotencyKey);

            ResponseEntity<WorkorderResponse> idempotentResponse = getIdempotentResponseIfPresent(idempotencyKey);
            if (idempotentResponse != null) {
                return idempotentResponse;
            }

            var workorder = workorderService.createWorkorder(estimateId, null);
            WorkorderResponse response = WorkorderResponse.fromEntity(workorder);

            ResponseEntity<WorkorderResponse> raceConditionResponse = registerIdempotencyKeyAndHandleRaceCondition(
                    idempotencyKey, response);
            if (raceConditionResponse != null) {
                return raceConditionResponse;
            }

            log.info("Successfully promoted estimate {} to workorder {}", estimateId, response.getId());
            return ResponseEntity.ok(response);

        } catch (PromotionValidationException e) {
            ResponseEntity<WorkorderResponse> alreadyPromotedResponse = handleAlreadyPromoted(estimateId, e);
            if (alreadyPromotedResponse != null) {
                return alreadyPromotedResponse;
            }

            log.warn("Promotion validation failed for estimate {}: {} - {}",
                    estimateId, e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();

        } catch (EntityNotFoundException e) {
            log.warn("Estimate {} not found", estimateId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        } catch (IllegalArgumentException e) {
            log.warn("Invalid argument promoting estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Unexpected error promoting estimate {}", estimateId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private @Nullable ResponseEntity<WorkorderResponse> getIdempotentResponseIfPresent(String idempotencyKey) {
        if (!hasIdempotencyKey(idempotencyKey)) {
            return null;
        }

        var existingWorkorderId = idempotencyService.getExistingWorkorderId(idempotencyKey);
        if (existingWorkorderId.isEmpty()) {
            return null;
        }

        UUID workorderId = existingWorkorderId.get();
        log.info("Idempotency key {} already processed - returning existing workorder {}",
                idempotencyKey, workorderId);
        return loadWorkorderResponse(
                workorderId,
                () -> {
                    log.error("Existing workorder {} not found for idempotency key {} - data inconsistency",
                            workorderId, idempotencyKey);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    private @Nullable ResponseEntity<WorkorderResponse> registerIdempotencyKeyAndHandleRaceCondition(
            String idempotencyKey,
            WorkorderResponse currentResponse) {
        if (!hasIdempotencyKey(idempotencyKey)) {
            return null;
        }

        try {
            idempotencyService.registerKey(idempotencyKey, currentResponse.getId());
            return null;
        } catch (DataIntegrityViolationException e) {
            var existingWorkorderId = idempotencyService.getExistingWorkorderId(idempotencyKey);
            if (existingWorkorderId.isPresent() && !existingWorkorderId.get().equals(currentResponse.getId())) {
                UUID workorderId = existingWorkorderId.get();
                log.warn("Race condition detected: idempotency key {} already registered for different workorder {}",
                        idempotencyKey, workorderId);
                return loadWorkorderResponse(workorderId,
                        () -> ResponseEntity.status(HttpStatus.OK).body(currentResponse));
            }
            log.debug("Idempotency key {} already registered for current workorder {}",
                    idempotencyKey, currentResponse.getId());
            return null;
        }
    }

    private @Nullable ResponseEntity<WorkorderResponse> handleAlreadyPromoted(
            UUID estimateId,
            PromotionValidationException exception) {
        if (exception.getErrorCode() != PromotionValidationException.PromotionErrorCode.ALREADY_PROMOTED
                || exception.getExistingWorkorderId() == null) {
            return null;
        }

        UUID existingWorkorderId = exception.getExistingWorkorderId();
        log.info("Estimate {} already promoted to workorder {} (idempotent retry)",
                estimateId, existingWorkorderId);
        return loadWorkorderResponse(
                existingWorkorderId,
                () -> {
                    log.error("Existing workorder {} not found after ALREADY_PROMOTED validation",
                            existingWorkorderId);
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                });
    }

    private ResponseEntity<WorkorderResponse> loadWorkorderResponse(
            UUID workorderId,
            Supplier<ResponseEntity<WorkorderResponse>> fallback) {
        return workorderService.getWorkorderById(workorderId)
                .map(WorkorderResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElseGet(fallback);
    }

    private boolean hasIdempotencyKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    @Operation(summary = "Submit estimate for customer approval", description = "Submit a DRAFT estimate for customer approval. Creates immutable snapshot and transitions to PENDING_APPROVAL state. "
            + "Validates completeness (has customer, vehicle, line items, calculated totals). "
            + "CAP:003 Issue #168 - Submit Estimate for Customer Approval")
    @ApiResponse(responseCode = "200", description = "Estimate submitted for approval successfully")
    @ApiResponse(responseCode = "400", description = "Estimate is incomplete or not in DRAFT state")
    @ApiResponse(responseCode = "404", description = "Estimate not found")
    @PostMapping("/{estimateId}/submit-for-approval")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SUBMIT", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:submit')")
    public ResponseEntity<EstimateResponse> submitForApproval(
            @Parameter(description = "ID of the estimate to submit", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        try {
            // Get authenticated username from security context
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateResponse submitted = estimateService.submitForApproval(estimateId, username);
            return ResponseEntity.ok(submitted);
        } catch (EntityNotFoundException e) {
            log.warn("Estimate {} not found: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Failed to submit estimate {} for approval: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete an estimate", description = "Delete an estimate by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Estimate deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @DeleteMapping("/{estimateId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_DELETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:delete')")
    public ResponseEntity<Void> deleteEstimate(
            @Parameter(description = "ID of the estimate to delete", example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        estimateService.deleteEstimate(estimateId);
        return ResponseEntity.noContent().build();
    }

    // ==================== ESTIMATE ITEM MANAGEMENT (CAP:002 Stories #14, #15, #17)
    // ====================

    @Operation(summary = "Add line item to estimate", description = "Add a part or labor line item to a draft estimate. Estimate must be in DRAFT status. "
            +
            "For PART items, provide productId or description. For LABOR items, provide serviceId or description.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Line item added successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid request"),
            @ApiResponse(responseCode = "404", description = "Estimate not found"),
            @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Line item details", required = true, content = @Content(schema = @Schema(implementation = AddEstimateItemRequest.class), examples = @ExampleObject(name = "addEstimateItem", value = "{\"itemType\":\"LABOR\",\"description\":\"Brake inspection\",\"quantity\":1,\"unitPrice\":129.99,\"taxCode\":\"LABOR_STANDARD\"}")))
    @PostMapping("/{estimateId}/items")
    @EmitEvent(id = "ESTIMATE_ITEM_ADD", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate_item:add')")
    public ResponseEntity<EstimateItemResponse> addEstimateItem(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Line item details", required = true) @Valid @RequestBody AddEstimateItemRequest request) {
        try {
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateItemResponse item = estimateService.addEstimateItem(estimateId, request, username);
            return ResponseEntity.ok(item);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Estimate {} not found when adding item: {}", estimateId, e.getReason());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                log.warn("Validation error adding item to estimate {}: {}", estimateId, e.getReason());
                return ResponseEntity.badRequest().build();
            }
            throw e;
        } catch (jakarta.persistence.EntityNotFoundException e) {
            log.warn("Estimate {} not found when adding item: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalArgumentException e) {
            log.warn("Validation error adding item to estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("State error adding item to estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(summary = "Update line item", description = "Update an existing line item on a draft estimate. Estimate must be in DRAFT status. "
            +
            "Only provided fields will be updated.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Line item updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid request"),
            @ApiResponse(responseCode = "404", description = "Estimate or item not found"),
            @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated item fields", required = true, content = @Content(schema = @Schema(implementation = UpdateEstimateItemRequest.class), examples = @ExampleObject(name = "updateEstimateItem", value = "{\"description\":\"Brake inspection and adjustment\",\"quantity\":1,\"unitPrice\":149.99,\"taxCode\":\"LABOR_STANDARD\"}")))
    @PatchMapping("/{estimateId}/items/{itemId}")
    @EmitEvent(id = "ESTIMATE_ITEM_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate_item:edit')")
    public ResponseEntity<EstimateItemResponse> updateEstimateItem(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Item ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable UUID itemId,
            @Parameter(description = "Updated item fields", required = true) @Valid @RequestBody UpdateEstimateItemRequest request) {
        try {
            EstimateItemResponse item = estimateService.updateEstimateItem(estimateId, itemId, request);
            return ResponseEntity.ok(item);
        } catch (EntityNotFoundException e) {
            log.warn("Estimate or item not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalArgumentException e) {
            log.warn("Validation error updating item {} on estimate {}: {}", itemId, estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            log.warn("State error updating item {} on estimate {}: {}", itemId, estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(summary = "Remove line item", description = "Remove a line item from a draft estimate (soft delete). Estimate must be in DRAFT status.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Line item removed successfully"),
            @ApiResponse(responseCode = "404", description = "Estimate or item not found"),
            @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
    })
    @DeleteMapping("/{estimateId}/items/{itemId}")
    @EmitEvent(id = "ESTIMATE_ITEM_DELETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate_item:delete')")
    public ResponseEntity<Void> deleteEstimateItem(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Item ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable UUID itemId) {
        try {
            estimateService.deleteEstimateItem(estimateId, itemId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Item {} not found for estimate {}: {}", itemId, estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            log.warn("State error deleting item {} from estimate {}: {}", itemId, estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // ==================== TAX CALCULATION (CAP:002 Story #16) ====================

    @Operation(summary = "Calculate taxes and totals", description = "Calculate subtotal, tax amount, and total for an estimate based on its line items. "
            +
            "Estimate must be in DRAFT status. Uses stub tax calculation (8.25% flat rate) pending "
            +
            "pos-accounting integration.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Totals calculated successfully"),
            @ApiResponse(responseCode = "404", description = "Estimate not found"),
            @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
    })
    @PostMapping("/{estimateId}/calculate")
    @EmitEvent(id = "ESTIMATE_CALCULATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:calculate')")
    public ResponseEntity<Map<String, Object>> calculateEstimateTotals(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        try {
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateResponse estimate = estimateService.calculateEstimateTaxesAndTotals(estimateId, username);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("estimate", estimate);
            response.put("subtotal", estimate.getSubtotal());
            response.put("taxAmount", estimate.getTaxAmount());
            response.put("total", estimate.getTotal());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Estimate {} not found for calculation: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            log.warn("State error calculating estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // ==================== ESTIMATE SUMMARY (CAP:002 Story #18)
    // ====================

    @Operation(summary = "Get estimate summary (customer-facing)", description = "Retrieve a customer-facing summary of an estimate with grouped line items (parts and labor) "
            +
            "and financial breakdown. PDF generation not implemented (requires document service integration).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Summary retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Estimate not found")
    })
    @GetMapping("/{estimateId}/summary")
    @EmitEvent(id = "ESTIMATE_SUMMARY_VIEW", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate:view')")
    public ResponseEntity<EstimateSummaryResponse> getEstimateSummary(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId) {
        try {
            EstimateSummaryResponse summary = estimateService.getEstimateSummary(estimateId);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            log.warn("Estimate {} not found for summary: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(summary = "Create historical snapshot", description = "Capture an immutable snapshot of the estimate's complete state (estimate + all line items) "
            +
            "for audit trail and version history purposes.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Snapshot created successfully"),
            @ApiResponse(responseCode = "404", description = "Estimate not found"),
            @ApiResponse(responseCode = "409", description = "Snapshot creation failed")
    })
    @PostMapping("/{estimateId}/snapshots")
    @EmitEvent(id = "ESTIMATE_SNAPSHOT_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:estimate_snapshot:create')")
    public ResponseEntity<EstimateSnapshotResponse> createEstimateSnapshot(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID estimateId,
            @Parameter(description = "Optional notes about why snapshot was created", example = "Snapshot captured before approval") @RequestParam(required = false) @Nullable String notes) {
        try {
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateSnapshotResponse snapshot = estimateService.createEstimateSnapshot(estimateId, username, notes);
            return ResponseEntity.ok(snapshot);
        } catch (IllegalArgumentException e) {
            log.warn("Estimate {} not found for snapshot: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            log.error("Failed to create snapshot for estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
