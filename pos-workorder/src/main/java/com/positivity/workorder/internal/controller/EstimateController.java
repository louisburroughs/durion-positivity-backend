package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.dto.ApproveEstimateRequest;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateItemResponse;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.dto.EstimateSnapshotResponse;
import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.UpdateEstimateItemRequest;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.PromotionIdempotencyInconsistencyException;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.IdempotencyService;
import com.positivity.workorder.internal.service.WorkorderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Tag(name = "Estimate API", description = "Endpoints for estimate management and approval workflow")
@RestController
@RequestMapping("/v1/workorders/estimates")
@RequiredArgsConstructor
@Slf4j
public class EstimateController {
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String CONFLICT = "CONFLICT";
    private static final String SYSTEM = "SYSTEM";
    private static final String IDEMPOTENCY_OPERATION_ESTIMATE_CREATE = "estimate.create";
    private static final String IDEMPOTENCY_OPERATION_ESTIMATE_PROMOTE = "estimate.promote";
    private final EstimateService estimateService;
    private final WorkorderService workorderService;
    private final IdempotencyService idempotencyService;

    @Operation(operationId = "listEstimates", summary = "List All Estimates", description = """
                    Returns every estimate in the system as an unpaginated list, in all statuses from DRAFT \
                    through APPROVED, DECLINED, and EXPIRED.
                    Use this tool only for small datasets or admin views; use searchEstimates instead for \
                    paginated, filtered lookup by query, customer, or vehicle.
                    Preconditions: none beyond the caller holding workorder:estimate:view.
                    Required inputs: none — there are no filters or pagination parameters.
                    Emits a WORKORDER_ESTIMATE_LIST audit event; no estimate state changes — this is a read-only \
                    projection.
                    Returns 200 with the full list, possibly empty.
                    """)
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping
    @EmitEvent(id = "WORKORDER_ESTIMATE_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public List<EstimateResponse> getAllEstimates() {
        return estimateService.getAllEstimates();
    }

    @Operation(operationId = "getEstimate", summary = "Get Estimate by Id", description = """
                    Returns one estimate with its status, customer, vehicle, financial totals, and \
                    approval-related fields.
                    Use this tool when the estimate id is known; use getEstimateSummary instead for the \
                    customer-facing grouped view, or searchEstimates to find estimates by text.
                    Preconditions: the estimate must exist.
                    Required inputs: estimateId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no estimate exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Estimate found and returned.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @GetMapping("/{estimateId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public ResponseEntity<EstimateResponse> getEstimateById(
            @Parameter(description = "ID of the estimate to retrieve", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        return estimateService
                .getEstimateById(estimateId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "listEstimatesByCustomer", summary = "List Estimates for a Customer", description = """
                    Returns all estimates belonging to one customer, unpaginated and in every status.
                    Use this tool for a customer's estimate history; use searchEstimates instead when \
                    pagination or a combined vehicle filter is needed.
                    Preconditions: none — an unknown customerId simply yields an empty list.
                    Required inputs: customerId (UUID) as a path parameter.
                    Emits a WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER audit event; no estimate state changes — this \
                    is a read-only projection.
                    Returns 200 with the estimates, possibly empty; no 404 is produced for unknown customers.
                    """)
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/customer/{customerId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public List<EstimateResponse> getEstimatesByCustomer(
            @Parameter(description = "ID of the customer", example = "550e8400-e29b-41d4-a716-446655440010")
                    @PathVariable
                    UUID customerId) {
        return estimateService.getEstimatesByCustomer(customerId);
    }

    @Operation(operationId = "listEstimatesByShop", summary = "List Estimates for a Shop", description = """
                    Returns all estimates recorded against one shop location, unpaginated and in every status.
                    Use this tool when the caller's route vocabulary says shop; it is a legacy alias of \
                    listEstimatesByLocation and returns identical results, so use listEstimatesByLocation instead in \
                    new integrations.
                    Preconditions: none — an unknown locationId simply yields an empty list.
                    Required inputs: locationId (UUID) as a path parameter.
                    Emits a WORKORDER_ESTIMATE_SEARCH_BY_SHOP audit event; no estimate state changes — this is a \
                    read-only projection.
                    Returns 200 with the estimates, possibly empty.
                    """)
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/shop/{locationId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_SHOP", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public List<EstimateResponse> getEstimatesByShop(
            @Parameter(description = "ID of the shop", example = "550e8400-e29b-41d4-a716-446655440020") @PathVariable
                    UUID locationId) {
        return estimateService.getEstimatesByLocation(locationId);
    }

    @Operation(operationId = "listEstimatesByLocation", summary = "List Estimates for a Location", description = """
                    Returns all estimates recorded against one location, unpaginated and in every status.
                    Use this tool for a location's estimate book; do not use listEstimatesByShop, which is the \
                    legacy alias of this same lookup.
                    Preconditions: none — an unknown locationId simply yields an empty list.
                    Required inputs: locationId (UUID) as a path parameter.
                    Emits a WORKORDER_ESTIMATE_SEARCH_BY_LOCATION audit event; no estimate state changes — this \
                    is a read-only projection.
                    Returns 200 with the estimates, possibly empty.
                    """)
    @ApiResponse(responseCode = "200", description = "List of estimates returned successfully.")
    @GetMapping("/location/{locationId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SEARCH_BY_LOCATION", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public List<EstimateResponse> getEstimatesByLocation(
            @Parameter(description = "ID of the location", example = "550e8400-e29b-41d4-a716-446655440020")
                    @PathVariable
                    UUID locationId) {
        return estimateService.getEstimatesByLocation(locationId);
    }

    @Operation(operationId = "createEstimate", summary = "Create a New Draft Estimate", description = """
                    Creates an estimate in DRAFT status for a customer and vehicle, generating a unique estimate \
                    number and applying defaults for location and currency when omitted.
                    Use this tool for a from-scratch estimate; do not use createEstimateFromAppointment, which \
                    seeds the estimate from a scheduled appointment and is idempotent on the appointment id.
                    Preconditions: none beyond the caller holding workorder:estimate:create; when financial \
                    fields are supplied, subtotal plus taxAmount must equal total and none may be negative.
                    Required inputs: customerId and vehicleId (UUIDs), crmPartyId, crmVehicleId, and \
                    crmContactIds; locationId, currencyUomId, and the financial fields are optional, and an \
                    Idempotency-Key header replays the originally created estimate.
                    Emits a WORKORDER_ESTIMATE_CREATE event.
                    Returns 201 with the estimate, 400 with code VALIDATION_ERROR when required fields are \
                    missing or negative, and 409 with code CONFLICT when totals are inconsistent or an integrity \
                    constraint fails.
                    """)
    @ApiResponse(responseCode = "201", description = "Estimate created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request - missing required fields.")
    @ApiResponse(responseCode = "403", description = "Forbidden - user does not have ESTIMATE_CREATE permission.")
    @ApiResponse(
            responseCode = "409",
            description = "Conflict - estimate could not be created due to state or integrity constraints.")
    @ApiResponse(responseCode = "500", description = "Internal server error - unexpected estimate creation failure.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Customer, vehicle, and CRM references the draft estimate is opened for.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = CreateEstimateRequest.class),
                            examples = @ExampleObject(name = "createEstimate", value = """
                                                    {"customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4ac1",
                                                     "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4ac2",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4ac3",
                                                     "crmPartyId":"party-1001",
                                                     "crmVehicleId":"veh-2002",
                                                     "crmContactIds":["contact-3003"]}
                                                    """)))
    @PostMapping()
    @EmitEvent(id = "WORKORDER_ESTIMATE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:create"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_CREATE + "')")
    public ResponseEntity<Object> createEstimate(
            @Parameter(description = "Estimate creation request with customer and vehicle IDs") @Valid @RequestBody
                    CreateEstimateRequest request,
            @Parameter(
                            description = "Optional idempotency key for safe retries",
                            example = "estimate-create-550e8400-e29b-41d4-a716-446655440000")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        try {
            log.info(
                    "Received create estimate request for customerId(mask)={}, vehicleId(mask)={}",
                    maskForLog(request.getCustomerId()),
                    maskForLog(request.getVehicleId()));

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                var existingEstimateId = idempotencyService.getExistingWorkorderId(
                        IDEMPOTENCY_OPERATION_ESTIMATE_CREATE, idempotencyKey);
                if (existingEstimateId.isPresent()) {
                    return estimateService
                            .getEstimateById(existingEstimateId.get())
                            .<ResponseEntity<Object>>map(existing ->
                                    ResponseEntity.status(HttpStatus.CREATED).body(existing))
                            .orElseGet(() ->
                                    ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", CONFLICT)));
                }
            }

            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateResponse response = estimateService.createEstimate(request, username);

            registerCreateEstimateIdempotencyKeyIfPresent(idempotencyKey, response.getId());

            log.info("Estimate created successfully: id={}, number={}", response.getId(), response.getEstimateNumber());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (WorkorderRequestValidationException e) {
            log.warn("Validation error creating estimate: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", VALIDATION_ERROR));

        } catch (IllegalStateException e) {
            log.warn("Conflict creating estimate: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", CONFLICT));

        } catch (DataIntegrityViolationException e) {
            log.warn("Conflict creating estimate: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", CONFLICT));

        } catch (Exception e) {
            log.error("Unexpected error creating estimate", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void registerCreateEstimateIdempotencyKeyIfPresent(String idempotencyKey, UUID estimateId) {
        if (!hasIdempotencyKey(idempotencyKey)) {
            return;
        }

        try {
            idempotencyService.registerKey(IDEMPOTENCY_OPERATION_ESTIMATE_CREATE, idempotencyKey, estimateId);
        } catch (DataIntegrityViolationException _) {
            log.debug("Idempotency key(mask) {} already registered", maskForLog(idempotencyKey));
        }
    }

    @PatchMapping("/{estimateId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_PATCH", apiVersion = "1")
    @Operation(operationId = "patchEstimateStatus", summary = "Patch Estimate Status", description = """
                    Applies a workflow transition to an estimate by target status: DECLINED delegates to the \
                    decline flow without a reason, and DRAFT delegates to the reopen flow — no other target is \
                    accepted.
                    Use this tool only for generic UI status toggles; prefer declineEstimate, which records a \
                    decline reason, and reopenEstimate rather than this generic patch.
                    Preconditions: the estimate must exist and the delegated transition's own rules apply — \
                    decline requires DRAFT, PENDING_APPROVAL, or APPROVED status, and reopen requires DECLINED \
                    within the expiry window.
                    Required inputs: estimateId (UUID) as a path parameter and a JSON body whose status field is \
                    DECLINED or DRAFT.
                    Emits a WORKORDER_ESTIMATE_PATCH event.
                    Returns 400 with VALIDATION_ERROR when status is missing or not a known value, 404 when the \
                    estimate does not exist, and 409 with CONFLICT when the target is unsupported or the \
                    transition is not allowed from the current state.
                    """)
    @ApiResponse(responseCode = "200", description = "Estimate status updated")
    @ApiResponse(responseCode = "400", description = "Estimate patch request is invalid")
    @ApiResponse(
            responseCode = "404",
            description = "Estimate not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Estimate transition is not allowed")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:edit"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_EDIT + "')")
    public ResponseEntity<Object> patchEstimateStatus(
            @PathVariable UUID estimateId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Patch document naming the target estimate status (DECLINED or DRAFT).",
                            required = true,
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            name = "Decline via patch",
                                                            value = "{\"status\":\"DECLINED\"}")))
                    @RequestBody
                    Map<String, Object> patchRequest) {
        Object rawStatus = patchRequest.get("status");
        if (!(rawStatus instanceof String statusValue) || statusValue.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", VALIDATION_ERROR));
        }

        final EstimateStatus targetStatus;
        try {
            targetStatus = EstimateStatus.valueOf(statusValue);
        } catch (IllegalArgumentException _) {
            return ResponseEntity.badRequest().body(Map.of("code", VALIDATION_ERROR));
        }

        try {
            return switch (targetStatus) {
                case DECLINED -> ResponseEntity.ok(estimateService.declineEstimate(estimateId, null));
                case DRAFT -> ResponseEntity.ok(estimateService.reopenEstimate(estimateId));
                default -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", CONFLICT));
            };
        } catch (IllegalStateException _) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", CONFLICT));
        }
    }

    @Operation(operationId = "declineEstimate", summary = "Decline an Estimate", description = """
                    Declines an estimate, setting DECLINED status with the decline reason and timestamp and \
                    stamping an expiry date from the applicable approval configuration's declineExpiryDays.
                    Use this tool when the customer rejects the estimate; do not use reopenEstimate, which \
                    reverses a decline while the expiry window is still open.
                    Preconditions: the estimate must exist and be in DRAFT, PENDING_APPROVAL, or APPROVED \
                    status.
                    Required inputs: estimateId (UUID) as a path parameter; reason is an optional query \
                    parameter recorded as the decline reason.
                    Emits a WORKORDER_ESTIMATE_DECLINE event and marks the estimate fact changed.
                    Returns 400 when the estimate cannot be declined from its current status, and 404 when the \
                    estimate does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Estimate declined successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be declined in current state.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/decline")
    @EmitEvent(id = "WORKORDER_ESTIMATE_DECLINE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:decline"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_DECLINE + "')")
    public ResponseEntity<EstimateResponse> declineEstimate(
            @Parameter(description = "ID of the estimate to decline", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(description = "Reason for decline", example = "Customer declined additional work")
                    @RequestParam(required = false)
                    String reason) {
        try {
            EstimateResponse declined = estimateService.declineEstimate(estimateId, reason);
            return ResponseEntity.ok(declined);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "reopenEstimate", summary = "Reopen a Declined Estimate", description = """
                    Reopens a DECLINED estimate back to DRAFT, clearing the decline reason, decline timestamp, \
                    and expiry date.
                    Use this tool when a customer changes their mind after declining; do not use \
                    reopenWorkorder, which reopens a completed workorder rather than an estimate.
                    Preconditions: the estimate must be DECLINED and still within the declineExpiryDays window \
                    of its approval configuration — expired declines cannot be reopened.
                    Required inputs: estimateId (UUID) as a path parameter; there is no request body.
                    Emits a WORKORDER_ESTIMATE_REOPEN event and marks the estimate fact changed.
                    Returns 400 when the estimate is not declined or is past its expiry window, and 404 when \
                    the estimate does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Estimate reopened successfully.")
    @ApiResponse(responseCode = "400", description = "Estimate cannot be reopened (not declined or expired).")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @PostMapping("/{estimateId}/reopen")
    @EmitEvent(id = "WORKORDER_ESTIMATE_REOPEN", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:reopen"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_REOPEN + "')")
    public ResponseEntity<EstimateResponse> reopenEstimate(
            @Parameter(description = "ID of the estimate to reopen", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        try {
            EstimateResponse reopened = estimateService.reopenEstimate(estimateId);
            return ResponseEntity.ok(reopened);
        } catch (IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "approveEstimate", summary = "Approve Estimate With Customer Signature", description = """
                    Approves a PENDING_APPROVAL estimate, transitioning it to APPROVED with the captured \
                    signature, signer, notes, optional purchase order, and optional selective line-item \
                    approvals.
                    Use this tool when the customer accepts the submitted estimate; do not use approveWorkorder, \
                    which authorizes a workorder rather than an estimate — submitEstimateForApproval must have \
                    run first, and promoteEstimate is the follow-up that turns the approval into a workorder.
                    Preconditions: the estimate must exist in PENDING_APPROVAL status and belong to the \
                    customerId in the request; commercial accounts with PO enforcement must supply a \
                    purchaseOrderNumber.
                    Required inputs: estimateId (UUID) as a path parameter and customerId (UUID) in the body; \
                    signatureData, signerName, notes, purchaseOrderNumber, and lineItemApprovals are optional, \
                    and signatureMimeType defaults to image/png.
                    Emits a WORKORDER_ESTIMATE_APPROVE event.
                    Returns 404 when the estimate does not exist, 400 when the status is not PENDING_APPROVAL or \
                    the customerId in the request does not match the estimate's own customer, and 422 when a \
                    required purchase order is missing.
                    """)
    @ApiResponse(responseCode = "200", description = "Estimate approved successfully with signature captured.")
    @ApiResponse(
            responseCode = "400",
            description = "Estimate cannot be approved in current state, or the customerId in the request does "
                    + "not match the estimate's own customer.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @ApiResponse(
            responseCode = "422",
            description = "Purchase order number is required for this customer account (PURCHASE_ORDER_REQUIRED).")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Approving customer's identity, signature artifacts, and optional line-item selections.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ApproveEstimateRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "approveEstimate",
                                            value =
                                                    "{\"customerId\":\"550e8400-e29b-41d4-a716-446655440010\",\"signatureData\":\"base64-signature\",\"signatureMimeType\":\"image/png\",\"signerName\":\"Jane Customer\",\"notes\":\"Approved estimate\",\"purchaseOrderNumber\":\"PO-12345\"}")))
    @PostMapping("/{estimateId}/approval")
    @EmitEvent(id = "WORKORDER_ESTIMATE_APPROVE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:approve"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_APPROVE + "')")
    public ResponseEntity<EstimateResponse> approveEstimate(
            @Parameter(description = "ID of the estimate to approve", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(
                            description =
                                    "Approval request with customer ID, signature capture, and optional selective line item approvals")
                    @Valid
                    @RequestBody
                    ApproveEstimateRequest request) {
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
        } catch (IllegalStateException e) {
            log.warn("Failed to approve estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "promoteEstimate", summary = "Promote Approved Estimate to Workorder", description = """
                    Promotes an APPROVED estimate into a new DRAFT workorder that inherits the estimate's \
                    customer, location, and CRM references.
                    Use this tool after approveEstimate succeeds; do not use createWorkorder, which builds a \
                    workorder from an estimate id without the promotion validations.
                    Preconditions: the estimate must be APPROVED with a valid, unexpired approval, have approved \
                    items, and not already be promoted — a prior promotion is answered with the existing \
                    workorder instead of a duplicate.
                    Required inputs: estimateId (UUID) as a path parameter; an Idempotency-Key header collapses \
                    retries onto the originally created workorder.
                    Emits a WORKORDER_ESTIMATE_PROMOTE event.
                    Returns 200 with the workorder (also on ALREADY_PROMOTED replays that can resolve the \
                    existing workorder), 404 when the estimate does not exist, 409 when a promotion \
                    precondition or the customer's requirements verdict refuses it, and 503 when that verdict \
                    has not replicated yet — a retryable condition, and the only one worth retrying.
                    Every non-2xx answer carries the ApiError envelope with a machine-readable code and the \
                    correlation id that also appears in the server log line.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Workorder created successfully from estimate"),
                @ApiResponse(
                        responseCode = "404",
                        description = "Estimate not found (ESTIMATE_NOT_FOUND)",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "409",
                        description = """
                                A promotion precondition refused the request. The envelope's code names \
                                which: ALREADY_PROMOTED, APPROVAL_EXPIRED, APPROVAL_INVALID, \
                                APPROVAL_NOT_FOUND, NO_APPROVED_ITEMS, INVALID_STATE, \
                                CUSTOMER_REQUIREMENTS_NOT_MET, or CUSTOMER_APPROVAL_INVALID. None of these \
                                is resolved by retrying.""",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "503",
                        description = """
                                CUSTOMER_REQUIREMENTS_UNAVAILABLE - the customer's requirements verdict has \
                                not replicated from pos-customer yet. The request is well-formed; retry \
                                after the Retry-After interval.""",
                        content = @Content(schema = @Schema(implementation = ApiError.class)))
            })
    @PostMapping("/{estimateId}/promote")
    @EmitEvent(id = "WORKORDER_ESTIMATE_PROMOTE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:promote"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_PROMOTE + "')")
    public ResponseEntity<WorkorderResponse> promoteEstimateToWorkorder(
            @Parameter(description = "ID of the estimate to promote", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(
                            description = "Idempotency-Key header for safe retries",
                            example = "estimate-promote-550e8400-e29b-41d4-a716-446655440000")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {
        try {
            log.info(
                    "Promoting estimate(mask) {} to workorder (idempotencyKey(mask)={})",
                    maskForLog(estimateId),
                    maskForLog(idempotencyKey));

            ResponseEntity<WorkorderResponse> idempotentResponse = getIdempotentResponseIfPresent(idempotencyKey);
            if (idempotentResponse != null) {
                return idempotentResponse;
            }

            WorkorderResponse response = workorderService.createWorkorder(estimateId, null);

            ResponseEntity<WorkorderResponse> raceConditionResponse =
                    registerIdempotencyKeyAndHandleRaceCondition(idempotencyKey, response);
            if (raceConditionResponse != null) {
                return raceConditionResponse;
            }

            log.info("Successfully promoted estimate {} to workorder {}", estimateId, response.getId());
            return ResponseEntity.ok(response);

        } catch (PromotionValidationException e) {
            // The one case that is not an error: a replay of a promotion that already happened is
            // answered with the workorder it produced. Every other promotion failure — including an
            // ALREADY_PROMOTED whose workorder cannot be loaded — is rethrown so GlobalExceptionHandler
            // answers it with the canonical ApiError envelope (#1477). Catching it here to build a
            // bodiless status is what left callers unable to tell a wrong id from a stale replica.
            ResponseEntity<WorkorderResponse> alreadyPromotedResponse = handleAlreadyPromoted(estimateId, e);
            if (alreadyPromotedResponse != null) {
                return alreadyPromotedResponse;
            }

            log.warn(
                    "Promotion validation failed for estimate {}: {} - {}",
                    estimateId,
                    e.getErrorCode(),
                    e.getMessage());
            throw e;

        } catch (EntityNotFoundException _) {
            log.warn("Estimate {} not found", estimateId);
            throw new EstimateNotFoundException(estimateId);
        }
    }

    private @Nullable ResponseEntity<WorkorderResponse> getIdempotentResponseIfPresent(String idempotencyKey) {
        if (!hasIdempotencyKey(idempotencyKey)) {
            return null;
        }

        var existingWorkorderId =
                idempotencyService.getExistingWorkorderId(IDEMPOTENCY_OPERATION_ESTIMATE_PROMOTE, idempotencyKey);
        if (existingWorkorderId.isEmpty()) {
            return null;
        }

        UUID workorderId = existingWorkorderId.get();
        log.info(
                "Idempotency key(mask) {} already processed - returning existing workorder(mask) {}",
                maskForLog(idempotencyKey),
                maskForLog(workorderId));
        return loadWorkorderResponse(workorderId, () -> {
            log.error(
                    "Existing workorder(mask) {} not found for idempotency key(mask) {} - data inconsistency",
                    maskForLog(workorderId),
                    maskForLog(idempotencyKey));
            throw new PromotionIdempotencyInconsistencyException(workorderId);
        });
    }

    private @Nullable ResponseEntity<WorkorderResponse> registerIdempotencyKeyAndHandleRaceCondition(
            String idempotencyKey, WorkorderResponse currentResponse) {
        if (!hasIdempotencyKey(idempotencyKey)) {
            return null;
        }

        try {
            idempotencyService.registerKey(
                    IDEMPOTENCY_OPERATION_ESTIMATE_PROMOTE, idempotencyKey, currentResponse.getId());
            return null;
        } catch (DataIntegrityViolationException _) {
            var existingWorkorderId =
                    idempotencyService.getExistingWorkorderId(IDEMPOTENCY_OPERATION_ESTIMATE_PROMOTE, idempotencyKey);
            if (existingWorkorderId.isPresent() && !existingWorkorderId.get().equals(currentResponse.getId())) {
                UUID workorderId = existingWorkorderId.get();
                log.warn(
                        "Race condition detected: idempotency key(mask) {} already registered for different workorder(mask) {}",
                        maskForLog(idempotencyKey),
                        maskForLog(workorderId));
                return loadWorkorderResponse(
                        workorderId, () -> ResponseEntity.status(HttpStatus.OK).body(currentResponse));
            }
            log.debug(
                    "Idempotency key(mask) {} already registered for current workorder(mask) {}",
                    maskForLog(idempotencyKey),
                    maskForLog(currentResponse.getId()));
            return null;
        }
    }

    private @Nullable ResponseEntity<WorkorderResponse> handleAlreadyPromoted(
            UUID estimateId, PromotionValidationException exception) {
        if (exception.getErrorCode() != PromotionValidationException.PromotionErrorCode.ALREADY_PROMOTED
                || exception.getExistingWorkorderId() == null) {
            return null;
        }

        UUID existingWorkorderId = exception.getExistingWorkorderId();
        log.info("Estimate {} already promoted to workorder {} (idempotent retry)", estimateId, existingWorkorderId);
        return loadWorkorderResponse(existingWorkorderId, () -> {
            log.error("Existing workorder {} not found after ALREADY_PROMOTED validation", existingWorkorderId);
            // Null hands the exception back to the caller, which rethrows it: the advice answers
            // ALREADY_PROMOTED with its code and the unresolvable workorder id as referenceId,
            // rather than a 409 that says nothing (#1477).
            return null;
        });
    }

    private @Nullable ResponseEntity<WorkorderResponse> loadWorkorderResponse(
            UUID workorderId, Supplier<@Nullable ResponseEntity<WorkorderResponse>> fallback) {
        return workorderService
                .getWorkorderById(workorderId)
                .map(ResponseEntity::ok)
                .orElseGet(fallback);
    }

    private boolean hasIdempotencyKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    @Operation(
            operationId = "submitEstimateForApproval",
            summary = "Submit Estimate for Customer Approval",
            description = """
                    Submits a DRAFT estimate for customer approval, creating an immutable snapshot and \
                    transitioning it to PENDING_APPROVAL.
                    Use this tool when the draft is ready to present; do not use approveEstimate before \
                    submission, since approval requires PENDING_APPROVAL status, and calculateEstimateTotals \
                    should already have produced totals.
                    Preconditions: the estimate must be in DRAFT status and complete — a customer, a vehicle, at \
                    least one line item, and calculated totals are all required.
                    Required inputs: estimateId (UUID) as a path parameter; there is no request body, and the \
                    submitting user comes from the security context.
                    Emits a WORKORDER_ESTIMATE_SUBMIT event and persists a submission snapshot.
                    Returns 404 when the estimate does not exist, and 400 when it is not DRAFT or fails a \
                    completeness check.
                    """)
    @ApiResponse(responseCode = "200", description = "Estimate submitted for approval successfully")
    @ApiResponse(responseCode = "400", description = "Estimate is incomplete or not in DRAFT state")
    @ApiResponse(responseCode = "404", description = "Estimate not found")
    @PostMapping("/{estimateId}/submit-for-approval")
    @EmitEvent(id = "WORKORDER_ESTIMATE_SUBMIT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:submit"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_SUBMIT + "')")
    public ResponseEntity<EstimateResponse> submitForApproval(
            @Parameter(description = "ID of the estimate to submit", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        try {
            // Get authenticated username from security context
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateResponse submitted = estimateService.submitForApproval(estimateId, username);
            return ResponseEntity.ok(submitted);
        } catch (EntityNotFoundException e) {
            log.warn("Estimate {} not found: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            log.warn("Failed to submit estimate {} for approval: {}", estimateId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(operationId = "deleteEstimate", summary = "Delete an Estimate", description = """
                    Deletes an estimate row by id — a hard delete with no status guard, removing the estimate \
                    regardless of its lifecycle state.
                    Use this tool only for mistakenly created estimates; do not use declineEstimate, which \
                    records the customer's rejection while preserving the record.
                    Preconditions: none are enforced — deletion is idempotent and deleting an unknown id is a \
                    silent no-op.
                    Required inputs: estimateId (UUID) as a path parameter; there is no request body.
                    Emits a WORKORDER_ESTIMATE_DELETE event.
                    Returns 204 regardless of whether the estimate previously existed.
                    """)
    @ApiResponse(responseCode = "204", description = "Estimate deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Estimate not found.")
    @DeleteMapping("/{estimateId}")
    @EmitEvent(id = "WORKORDER_ESTIMATE_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:delete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_DELETE + "')")
    public ResponseEntity<Void> deleteEstimate(
            @Parameter(description = "ID of the estimate to delete", example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        estimateService.deleteEstimate(estimateId);
        return ResponseEntity.noContent().build();
    }

    // ==================== ESTIMATE ITEM MANAGEMENT (CAP:002 Stories #14, #15, #17)
    // ====================

    @Operation(operationId = "addEstimateItem", summary = "Add Line Item to Estimate", description = """
                    Adds a PART or LABOR line item to a draft estimate with quantity, unit price, and optional \
                    tax code.
                    Use this tool while building the draft; do not use updateEstimateItem, which edits an \
                    existing line, and run calculateEstimateTotals afterwards to refresh totals.
                    Preconditions: the estimate must be in DRAFT status; PART items need a productId or \
                    description, and LABOR items need a serviceId or description.
                    Required inputs: estimateId (UUID) as a path parameter, plus itemType (PART or LABOR), \
                    quantity, and unitPrice in the body; description, taxCode, productId, serviceId, and uomCode \
                    are conditional or optional. uomCode is the unit quantity is expressed in for PART items \
                    (omit for the product's base unit); it must be omitted on LABOR items.
                    Emits an ESTIMATE_ITEM_ADD event.
                    Returns 404 when the estimate does not exist, 400 on validation failures (including a \
                    uomCode on a LABOR item), 422 when the referenced product has no conversion row for uomCode \
                    or the converted quantity exceeds the product's declared decimal scale, and 409 when the \
                    estimate is not in DRAFT status.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Line item added successfully"),
                @ApiResponse(responseCode = "400", description = "Validation error or invalid request"),
                @ApiResponse(responseCode = "404", description = "Estimate not found"),
                @ApiResponse(
                        responseCode = "422",
                        description = "uomCode has no conversion row for the product (UOM_CONVERSION_UNDEFINED), "
                                + "or the converted quantity exceeds the product's declared decimal scale "
                                + "(FRACTIONAL_QUANTITY_NOT_ALLOWED)"),
                @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Part or labor line being added to the draft estimate.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = AddEstimateItemRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "addEstimateItem",
                                            value =
                                                    "{\"itemType\":\"LABOR\",\"description\":\"Brake inspection\",\"quantity\":1,\"unitPrice\":129.99,\"taxCode\":\"LABOR_STANDARD\"}")))
    @PostMapping("/{estimateId}/items")
    @EmitEvent(id = "ESTIMATE_ITEM_ADD", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate_item:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_ITEM_ADD + "')")
    public ResponseEntity<EstimateItemResponse> addEstimateItem(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(description = "Line item details", required = true) @Valid @RequestBody
                    AddEstimateItemRequest request) {
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
        } catch (IllegalStateException e) {
            log.warn("State error adding item to estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(operationId = "updateEstimateItem", summary = "Update Estimate Line Item", description = """
                    Updates an existing line item on a draft estimate, changing only the fields provided and \
                    leaving omitted fields untouched.
                    Use this tool to adjust a line's description, quantity, price, or tax code; do not use \
                    addEstimateItem, which appends a new line, or deleteEstimateItem, which removes one.
                    Preconditions: the estimate must be in DRAFT status and the item must exist on that \
                    estimate.
                    Required inputs: estimateId and itemId (UUIDs) as path parameters; description, quantity, \
                    unitPrice, taxCode, and uomCode are all optional body fields. uomCode is rejected on a \
                    LABOR item.
                    Emits an ESTIMATE_ITEM_UPDATE event; totals are not recalculated until \
                    calculateEstimateTotals runs.
                    Returns 404 when the estimate or item does not exist, 400 on validation failures (including a \
                    uomCode on a LABOR item), 422 when the effective uomCode has no conversion row for the \
                    product or the converted quantity exceeds its declared decimal scale, and 409 when the \
                    estimate is not in DRAFT status.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Line item updated successfully"),
                @ApiResponse(responseCode = "400", description = "Validation error or invalid request"),
                @ApiResponse(responseCode = "404", description = "Estimate or item not found"),
                @ApiResponse(
                        responseCode = "422",
                        description = "uomCode has no conversion row for the product (UOM_CONVERSION_UNDEFINED), "
                                + "or the converted quantity exceeds the product's declared decimal scale "
                                + "(FRACTIONAL_QUANTITY_NOT_ALLOWED)"),
                @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Fields to change on the line item; omitted fields stay as they are.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = UpdateEstimateItemRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "updateEstimateItem",
                                            value =
                                                    "{\"description\":\"Brake inspection and adjustment\",\"quantity\":1,\"unitPrice\":149.99,\"taxCode\":\"LABOR_STANDARD\"}")))
    @PatchMapping("/{estimateId}/items/{itemId}")
    @EmitEvent(id = "ESTIMATE_ITEM_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate_item:edit"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_ITEM_EDIT + "')")
    public ResponseEntity<EstimateItemResponse> updateEstimateItem(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(description = "Item ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID itemId,
            @Parameter(description = "Updated item fields", required = true) @Valid @RequestBody
                    UpdateEstimateItemRequest request) {
        try {
            EstimateItemResponse item = estimateService.updateEstimateItem(estimateId, itemId, request);
            return ResponseEntity.ok(item);
        } catch (EntityNotFoundException e) {
            log.warn("Estimate or item not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            log.warn("State error updating item {} on estimate {}: {}", itemId, estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(operationId = "deleteEstimateItem", summary = "Remove Estimate Line Item", description = """
                    Removes a line item from a draft estimate as a soft delete, so the row is flagged deleted \
                    and excluded from future calculations rather than destroyed.
                    Use this tool to drop an unwanted line; do not use deleteEstimate, which hard-deletes the \
                    whole estimate.
                    Preconditions: the estimate must be in DRAFT status and the item must exist on that \
                    estimate.
                    Required inputs: estimateId and itemId (UUIDs) as path parameters; there is no request body.
                    Emits an ESTIMATE_ITEM_DELETE event; totals are not recalculated until \
                    calculateEstimateTotals runs.
                    Returns 404 when the estimate or item does not exist, and 409 when the estimate is not in \
                    DRAFT status.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "204", description = "Line item removed successfully"),
                @ApiResponse(responseCode = "404", description = "Estimate or item not found"),
                @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
            })
    @DeleteMapping("/{estimateId}/items/{itemId}")
    @EmitEvent(id = "ESTIMATE_ITEM_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate_item:delete"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_ITEM_DELETE + "')")
    public ResponseEntity<Void> deleteEstimateItem(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(description = "Item ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID itemId) {
        try {
            estimateService.deleteEstimateItem(estimateId, itemId);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            log.warn("State error deleting item {} from estimate {}: {}", itemId, estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // ==================== TAX CALCULATION (CAP:002 Story #16) ====================

    @Operation(
            operationId = "calculateEstimateTotals",
            summary = "Calculate Estimate Taxes and Totals",
            description = """
                    Calculates and persists the estimate's subtotal, tax amount, and total from its non-deleted \
                    line items, delegating tax to the tax service against the jurisdiction of the estimate's \
                    location.
                    Use this tool after editing line items and before submitEstimateForApproval; do not use \
                    getEstimateSummary, which reads the stored figures without recalculating.
                    Preconditions: the estimate must be in DRAFT status; an estimate with no line items has its \
                    totals zeroed.
                    Required inputs: estimateId (UUID) as a path parameter; there is no request body.
                    Emits an ESTIMATE_CALCULATE event; when the tax service is unreachable the subtotal is \
                    summed locally, tax is stored as zero, and the estimate is flagged taxPending with no \
                    fallback rate applied.
                    Returns 404 when the estimate does not exist, and 409 when it is not in DRAFT status.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Totals calculated successfully"),
                @ApiResponse(responseCode = "404", description = "Estimate not found"),
                @ApiResponse(responseCode = "409", description = "Estimate not in DRAFT status (INVALID_STATE)")
            })
    @PostMapping("/{estimateId}/calculate")
    @EmitEvent(id = "ESTIMATE_CALCULATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:calculate"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_CALCULATE + "')")
    public ResponseEntity<Map<String, Object>> calculateEstimateTotals(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        try {
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateResponse estimate = estimateService.calculateEstimateTaxesAndTotals(estimateId, username);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("estimate", estimate);
            response.put("subtotal", estimate.getSubtotal());
            response.put("taxAmount", estimate.getTaxAmount());
            response.put("total", estimate.getTotal());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("State error calculating estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // ==================== ESTIMATE SUMMARY (CAP:002 Story #18)
    // ====================

    @Operation(operationId = "getEstimateSummary", summary = "Get Customer-Facing Estimate Summary", description = """
                    Returns the customer-facing summary of an estimate with line items grouped into parts and \
                    labor plus the financial breakdown.
                    Use this tool for presentation to the customer; use getEstimate instead for the raw record, and \
                    generateEstimatePdf to render the same content as a PDF document.
                    Preconditions: the estimate must exist; totals reflect the last calculateEstimateTotals run.
                    Required inputs: estimateId (UUID) as a path parameter.
                    Emits an ESTIMATE_SUMMARY_VIEW audit event; no estimate state changes — this is a read-only \
                    projection.
                    Returns 404 when no estimate exists for the id.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Summary retrieved successfully"),
                @ApiResponse(responseCode = "404", description = "Estimate not found")
            })
    @GetMapping("/{estimateId}/summary")
    @EmitEvent(id = "ESTIMATE_SUMMARY_VIEW", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public ResponseEntity<EstimateSummaryResponse> getEstimateSummary(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        EstimateSummaryResponse summary = estimateService.getEstimateSummary(estimateId);
        return ResponseEntity.ok(summary);
    }

    @Operation(operationId = "generateEstimatePdf", summary = "Generate Estimate PDF Document", description = """
                    Renders the estimate as a PDF via the pos-documents service, containing header details, \
                    line items grouped into parts and labor, and financial totals, returned as an attachment.
                    Use this tool when a printable or emailable document is needed; use getEstimateSummary instead \
                    for the same content as JSON.
                    Preconditions: the estimate must exist and the pos-documents service must be reachable.
                    Required inputs: estimateId (UUID) as a path parameter.
                    Emits an ESTIMATE_PDF_GENERATE audit event; no estimate state changes — the render is \
                    performed on demand and not stored.
                    Returns 404 when the estimate does not exist, and 502 when the document service fails to \
                    render the PDF.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "PDF generated successfully",
                        content =
                                @Content(
                                        mediaType = "application/pdf",
                                        schema =
                                                @Schema(
                                                        type = "string",
                                                        format = "binary",
                                                        implementation = String.class))),
                @ApiResponse(responseCode = "404", description = "Estimate not found"),
                @ApiResponse(responseCode = "502", description = "Document service unavailable")
            })
    @GetMapping(value = "/{estimateId}/pdf", produces = "application/pdf")
    @EmitEvent(id = "ESTIMATE_PDF_GENERATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_VIEW + "')")
    public ResponseEntity<byte[]> generateEstimatePdf(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId) {
        try {
            byte[] pdfBytes = estimateService.generateEstimatePdf(estimateId);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"estimate-" + estimateId + ".pdf\"")
                    .header("Content-Type", "application/pdf")
                    .body(pdfBytes);
        } catch (EstimateNotFoundException e) {
            log.warn("Estimate {} not found for PDF generation: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Failed to generate PDF for estimate {}: {}", estimateId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @Operation(
            operationId = "createEstimateSnapshot",
            summary = "Create Estimate Historical Snapshot",
            description = """
                    Captures an immutable snapshot of the estimate's complete state — the estimate plus all its \
                    line items — for audit trail and version history.
                    Use this tool to preserve state before a significant change; submitEstimateForApproval \
                    already snapshots automatically, so do not duplicate it around submission.
                    Preconditions: the estimate must exist; snapshots are additive and never modify the \
                    estimate itself.
                    Required inputs: estimateId (UUID) as a path parameter; notes is an optional query \
                    parameter explaining why the snapshot was taken.
                    Emits an ESTIMATE_SNAPSHOT_CREATE event.
                    Returns 404 when the estimate does not exist, and 409 when the snapshot cannot be \
                    serialized.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Snapshot created successfully"),
                @ApiResponse(responseCode = "404", description = "Estimate not found"),
                @ApiResponse(responseCode = "409", description = "Snapshot creation failed")
            })
    @PostMapping("/{estimateId}/snapshots")
    @EmitEvent(id = "ESTIMATE_SNAPSHOT_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate_snapshot:create"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_SNAPSHOT_CREATE + "')")
    public ResponseEntity<EstimateSnapshotResponse> createEstimateSnapshot(
            @Parameter(description = "Estimate ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID estimateId,
            @Parameter(
                            description = "Optional notes about why snapshot was created",
                            example = "Snapshot captured before approval")
                    @RequestParam(required = false)
                    @Nullable
                    String notes) {
        try {
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
            EstimateSnapshotResponse snapshot = estimateService.createEstimateSnapshot(estimateId, username, notes);
            return ResponseEntity.ok(snapshot);
        } catch (IllegalStateException e) {
            log.error("Failed to create snapshot for estimate {}: {}", estimateId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
