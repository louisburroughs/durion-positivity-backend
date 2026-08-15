package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.scrap.ApproveScrapRequest;
import com.positivity.inventory.internal.dto.scrap.CreateScrapRequest;
import com.positivity.inventory.internal.dto.scrap.RejectScrapRequest;
import com.positivity.inventory.internal.dto.scrap.ScrapResponse;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.ScrapService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the scrap/write-off workflow (odoo-parity D1, issue #1030).
 *
 * <p>Scraps are evaluated against value-based approval thresholds on creation; below-threshold
 * scraps auto-post {@code SCRAP_OUT} in the create transaction, above-threshold (or
 * unknown-value) scraps require approval before posting.
 */
@RestController
@RequestMapping("/v1/inventory/scraps")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Scraps", description = "Scrap (write-off) documents with value-threshold approval and SCRAP_OUT posting")
public class ScrapController {

    private final ScrapService scrapService;

    /**
     * Creates a scrap document; below-threshold scraps auto-approve and post immediately.
     *
     * @param request the scrap creation request
     * @return the created scrap with its resulting status
     */
    @PostMapping
    @EmitEvent(id = "INVENTORY_SCRAP_CREATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.SCRAP_CREATE + "')")
    @Operation(
            operationId = "createScrap",
            summary = "Create scrap document",
            description = """
                    Creates a scrap (write-off) document for stock at a location and evaluates its value — \
                    quantity times the latest-receipt cost snapshot — against the configured approval thresholds: \
                    below all thresholds it is AUTO_APPROVED and its SCRAP_OUT ledger entry posts in the same \
                    transaction, while above them, or when no cost is derivable, it enters PENDING_APPROVAL.
                    Use this tool to write off damaged, expired or lost stock; do not use \
                    createCycleCountAdjustment, which settles a counted variance rather than a deliberate \
                    write-off.
                    Preconditions: the caller must be an authenticated user; a LOT-tracked SKU must name an \
                    existing ACTIVE lot, and the auto-approve posting requires sufficient on-hand at the posting \
                    location (the storage location when given, else the location) unless an authorized \
                    negative-stock override is passed.
                    Required inputs: stockItemId, quantity (positive), locationId and reasonCode (DAMAGED, \
                    EXPIRED, LOST, RECALLED, CONTAMINATED, WARRANTY_DESTROYED or OTHER — OTHER requires notes); \
                    storageLocationId, lotNumber, workorderId and attachmentReference are optional, and \
                    shouldReplenish and negativeStockOverride default to false.
                    Emits an INVENTORY_SCRAP_CREATE event, and a posted scrap additionally emits a ScrapPostedV1 \
                    fact, reduces on-hand immediately, and triggers a best-effort replenishment evaluation when \
                    shouldReplenish is true.
                    Returns 400 when reasonCode is OTHER without notes, 403 when negativeStockOverride is \
                    requested without inventory:adjustment:override, and 422 for insufficient on-hand \
                    (SCRAP_INSUFFICIENT_STOCK) or lot violations (LOT_NUMBER_REQUIRED, LOT_UNKNOWN, \
                    LOT_NOT_AVAILABLE).
                    """,
            tags = {"Scraps"})
    @ApiResponse(responseCode = "201", description = "Scrap created (AUTO_APPROVED/POSTED or PENDING_APPROVAL)")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request (e.g. OTHER reason without notes)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:scrap:create, or override requested without inventory:adjustment:override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Insufficient on-hand for the auto-approved posting (SCRAP_INSUFFICIENT_STOCK):"
                    + " reconcile via cycle count or adjustment, or use an authorized negative-stock override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> createScrap(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Stock write-off to record: what is scrapped, where, how much, and why.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Damaged stock write-off", value = """
                                                                    {"stockItemId":"OIL-5W30-5QT",
                                                                     "quantity":3,
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "storageLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "reasonCode":"DAMAGED",
                                                                     "notes":"Dropped during putaway, casing cracked",
                                                                     "shouldReplenish":false,
                                                                     "negativeStockOverride":false}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateScrapRequest request) {
        log.info("Received request to scrap {} of {}", request.getQuantity(), request.getStockItemId());
        return ResponseEntity.status(HttpStatus.CREATED).body(scrapService.createScrap(request));
    }

    /**
     * Approves a pending scrap and posts it to the ledger.
     *
     * @param scrapId the scrap document id
     * @param request the approval request (optional negative-stock override)
     * @return the approved and posted scrap
     */
    @PostMapping("/{scrapId}/approve")
    @EmitEvent(id = "INVENTORY_SCRAP_APPROVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:approve"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.SCRAP_APPROVE + "')")
    @Operation(
            operationId = "approveScrap",
            summary = "Approve scrap",
            description = """
                    Approves a PENDING_APPROVAL scrap and posts its SCRAP_OUT entry to the inventory ledger, \
                    reducing on-hand at the posting location and emitting a ScrapPostedV1 fact.
                    Use this tool after reviewing an above-threshold or unknown-value scrap; do not use \
                    rejectScrap, which discards it without touching inventory.
                    Preconditions: the scrap must exist and be in PENDING_APPROVAL status, and sufficient on-hand \
                    must exist at the posting location unless an authorized negative-stock override is passed — an \
                    insufficient-stock rejection rolls back and the scrap stays PENDING_APPROVAL.
                    Required inputs: scrapId (UUID) path parameter; the body carries only negativeStockOverride \
                    (default false), honored only when the caller holds inventory:adjustment:override; the \
                    approving actor comes from the authenticated context.
                    Emits an INVENTORY_SCRAP_APPROVE event plus the ScrapPostedV1 fact on the posting, and a scrap \
                    created with shouldReplenish triggers a best-effort replenishment evaluation.
                    Returns 404 when the scrap does not exist, 409 when it is not PENDING_APPROVAL, 403 when the \
                    override is requested without the permission, and 422 (SCRAP_INSUFFICIENT_STOCK) when on-hand \
                    is insufficient — reconcile via cycle count or adjustment, or use an authorized override.
                    """,
            tags = {"Scraps"})
    @ApiResponse(responseCode = "200", description = "Scrap approved and posted")
    @ApiResponse(
            responseCode = "403",
            description =
                    "Missing inventory:scrap:approve, or override requested without inventory:adjustment:override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Scrap not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Scrap is not in an approvable state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Insufficient on-hand (SCRAP_INSUFFICIENT_STOCK): reconcile via cycle count or"
                    + " adjustment, or use an authorized negative-stock override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> approveScrap(
            @Parameter(description = "Scrap document ID", required = true) @PathVariable UUID scrapId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Approval context; only the optional negative-stock override travels in"
                                    + " the body.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Approval without override", value = """
                                                                    {"negativeStockOverride":false}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ApproveScrapRequest request) {
        log.info("Received request to approve scrap {}", scrapId);
        return ResponseEntity.ok(scrapService.approveScrap(scrapId, request));
    }

    /**
     * Rejects a pending scrap; no inventory changes are made.
     *
     * @param scrapId the scrap document id
     * @param request the rejection request with reason
     * @return the rejected scrap
     */
    @PostMapping("/{scrapId}/reject")
    @EmitEvent(id = "INVENTORY_SCRAP_REJECT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:approve"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.SCRAP_APPROVE + "')")
    @Operation(
            operationId = "rejectScrap",
            summary = "Reject scrap",
            description = """
                    Rejects a PENDING_APPROVAL scrap with a recorded reason; no ledger posting or on-hand change \
                    is made and the document moves to the terminal REJECTED status.
                    Use this tool when the write-off should not happen (for example the part was recovered); do \
                    not use approveScrap, which posts the SCRAP_OUT entry.
                    Preconditions: the scrap must exist and be in PENDING_APPROVAL status; the rejecting actor is \
                    taken from the authenticated context, not the body.
                    Required inputs: scrapId (UUID) path parameter and rejectionReason (non-blank, max 1000 \
                    characters) in the body.
                    Emits an INVENTORY_SCRAP_REJECT event; inventory is untouched.
                    Returns 404 when the scrap does not exist, and 409 when it is not in PENDING_APPROVAL status.
                    """,
            tags = {"Scraps"})
    @ApiResponse(responseCode = "200", description = "Scrap rejected")
    @ApiResponse(
            responseCode = "404",
            description = "Scrap not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Scrap is not in a rejectable state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> rejectScrap(
            @Parameter(description = "Scrap document ID", required = true) @PathVariable UUID scrapId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rejection context recording why the write-off is discarded.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rejection", value = """
                                                                    {"rejectionReason":"Part was recovered and restocked"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RejectScrapRequest request) {
        log.info("Received request to reject scrap {}", scrapId);
        return ResponseEntity.ok(scrapService.rejectScrap(scrapId, request));
    }

    /**
     * Retrieves one scrap document.
     *
     * @param scrapId the scrap document id
     * @return the scrap
     */
    @GetMapping("/{scrapId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:view", "inventory:scrap:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.SCRAP_VIEW + "','"
            + InventoryPermissionRegistry.SCRAP_APPROVE + "')")
    @Operation(
            operationId = "getScrap",
            summary = "Get scrap details",
            description = """
                    Returns one scrap document with its quantity, cost snapshot and source, approval tier, \
                    lifecycle status and ledger-entry linkage.
                    Use this tool when the scrapId is already known; use listScraps instead to search by reason, \
                    status, location or date range.
                    Preconditions: the scrap must exist.
                    Required inputs: scrapId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no scrap exists for the supplied id.
                    """,
            tags = {"Scraps"})
    @ApiResponse(responseCode = "200", description = "Scrap found")
    @ApiResponse(
            responseCode = "404",
            description = "Scrap not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> getScrap(
            @Parameter(description = "Scrap document ID", required = true) @PathVariable UUID scrapId) {
        return ResponseEntity.ok(scrapService.getScrap(scrapId));
    }

    /**
     * Lists scrap documents matching the optional filters, newest first.
     *
     * @param reasonCode optional reason filter
     * @param status optional status filter
     * @param locationId optional location filter
     * @param createdFrom optional inclusive lower creation-time bound (ISO-8601 instant)
     * @param createdTo optional inclusive upper creation-time bound (ISO-8601 instant)
     * @return matching scraps
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:view", "inventory:scrap:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.SCRAP_VIEW + "','"
            + InventoryPermissionRegistry.SCRAP_APPROVE + "')")
    @Operation(
            operationId = "listScraps",
            summary = "List scraps",
            description = """
                    Returns scrap documents, newest first, optionally filtered by reason code, lifecycle status, \
                    location, and an inclusive creation-time range.
                    Use this tool to find pending approvals or audit write-off history; use getScrap instead when \
                    the scrapId is already known.
                    Preconditions: none.
                    Required inputs: reasonCode, status, locationId, createdFrom and createdTo (ISO-8601 \
                    instants) are all optional query parameters; there is no paging — the full match set is \
                    returned.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when no scraps match, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Scraps"})
    @ApiResponse(
            responseCode = "200",
            description = "Scraps retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ScrapResponse.class))))
    public ResponseEntity<List<ScrapResponse>> listScraps(
            @Parameter(description = "Filter by scrap reason") @RequestParam(required = false)
                    ScrapReasonCode reasonCode,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false) ScrapStatus status,
            @Parameter(description = "Filter by location") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Inclusive lower creation-time bound")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant createdFrom,
            @Parameter(description = "Inclusive upper creation-time bound")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant createdTo) {
        return ResponseEntity.ok(scrapService.listScraps(reasonCode, status, locationId, createdFrom, createdTo));
    }
}
