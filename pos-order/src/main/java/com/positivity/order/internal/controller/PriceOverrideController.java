package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.ApprovePriceOverrideRequest;
import com.positivity.order.internal.dto.RejectPriceOverrideRequest;
import com.positivity.order.internal.security.PriceOverridePermissions;
import com.positivity.order.service.PriceOverrideService;
import com.positivity.order.service.model.ApplyPriceOverrideRequest;
import com.positivity.order.service.model.ApproveOverrideCommand;
import com.positivity.order.service.model.PriceOverrideDetail;
import com.positivity.order.service.model.PriceOverrideResult;
import com.positivity.order.service.model.RejectOverrideCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for price override operations.
 *
 * Provides endpoints for:
 * - Applying price overrides
 * - Approving/rejecting overrides
 * - Querying override history
 */
@Tag(name = "Price Overrides", description = "Price override management with approval workflow")
@RestController
@RequestMapping("/v1/orders/price-overrides")
@RequiredArgsConstructor
@Slf4j
public class PriceOverrideController {

    private static final String UNKNOWN_REVIEWER_ROLE = "UNKNOWN";

    private final PriceOverrideService priceOverrideService;

    /**
     * Apply a price override to an order line.
     * Requires order:price_override:apply permission.
     */
    @Operation(
            operationId = "applyPriceOverride",
            summary = "Apply a Price Override",
            description = """
                    Applies a price override to a single DRAFT order line, auto-approving and repricing the line \
                    immediately when the discount is below the approval thresholds (10 percent and 50.00), and \
                    otherwise parking it at PENDING_APPROVAL for a manager.
                    Use this tool for a one-line price concession; do not use applyOrderDiscount, which spreads a \
                    discount pro-rata across the whole order, and do not use approvePriceOverride, which resolves \
                    an override that already exists.
                    Preconditions: the order must exist and be DRAFT, the order line must exist, and overridePrice \
                    must be between zero and originalPrice inclusive.
                    Required inputs: orderId, orderLineId, and productId as UUID strings, originalPrice, \
                    overridePrice, and reasonCode (for example PRICE_MATCH or GOODWILL_ADJUSTMENT); justification \
                    and idempotencyKey are optional, and a replayed key returns the existing override.
                    Emits an ORDER_PRICE_OVERRIDE_APPLY event; an auto-approved override immediately rewrites the \
                    line's unit price, recomputes the order subtotal, and publishes a commission-impact fact.
                    Returns 201 with status APPROVED or PENDING_APPROVAL, 400 when an id or reasonCode cannot be \
                    parsed, 409 when the idempotency key was used with a different payload, and 422 when the order \
                    or line cannot be found, the order is not DRAFT, or the override price is negative or above \
                    the original.
                    """,
            tags = {"Price Overrides"})
    @ApiResponse(
            responseCode = "201",
            description = "Override created",
            content = @Content(schema = @Schema(implementation = PriceOverrideResult.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PriceOverridePermissions.PRICE_OVERRIDE_APPLY})
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPLY + "')")
    @EmitEvent(id = "ORDER_PRICE_OVERRIDE_APPLY", apiVersion = "1")
    public ResponseEntity<PriceOverrideResult> applyPriceOverride(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The line-level override: original and overridden price with an audit" + " reason.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Price match", value = """
                                                                    {"orderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "orderLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                                                                     "originalPrice":49.99,
                                                                     "overridePrice":39.99,
                                                                     "reasonCode":"PRICE_MATCH",
                                                                     "justification":"Competitor price match per store policy",
                                                                     "idempotencyKey":"req-abc123"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ApplyPriceOverrideRequest request) {

        log.info("Applying price override for order {}", request.getOrderId());

        PriceOverrideResult result = priceOverrideService.applyPriceOverride(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Approve a pending price override.
     * Requires order:price_override:approve permission.
     */
    @Operation(
            operationId = "approvePriceOverride",
            summary = "Approve a Price Override",
            description = """
                    Approves a PENDING_APPROVAL price override, marking it APPROVED and recording an approval \
                    audit row with the reviewer's role resolved from the caller's granted roles.
                    Use this tool to grant a pending override; do not use rejectPriceOverride, which declines it, \
                    and do not use applyPriceOverride, which creates a new override.
                    Preconditions: the override must exist and be in PENDING_APPROVAL.
                    Required inputs: overrideId (UUID) as a path parameter; the body carries only optional \
                    reviewer comments — the approver identity and role come from the security context, not the \
                    body.
                    Emits an ORDER_PRICE_OVERRIDE_APPROVE event and publishes a commission-impact fact; note that \
                    the approval itself does not rewrite the order line's unit price — the line reprice happens \
                    only on the auto-approved apply path.
                    Returns 404 when the override does not exist, and 422 when the override is not in \
                    PENDING_APPROVAL.
                    """,
            tags = {"Price Overrides"})
    @ApiResponse(
            responseCode = "200",
            description = "Override approved",
            content = @Content(schema = @Schema(implementation = PriceOverrideDetail.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or override not in pending state")
    @ApiResponse(responseCode = "403", description = "Insufficient approval permissions")
    @ApiResponse(responseCode = "404", description = "Override not found")
    @PostMapping("/{overrideId}/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PriceOverridePermissions.PRICE_OVERRIDE_APPROVE})
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPROVE + "')")
    @EmitEvent(id = "ORDER_PRICE_OVERRIDE_APPROVE", apiVersion = "1")
    public ResponseEntity<PriceOverrideDetail> approvePriceOverride(
            @Parameter(
                            description = "Price override ID",
                            required = true,
                            example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional reviewer commentary recorded on the approval audit row.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Approval with comment",
                                                            value =
                                                                    "{\"comments\":\"Approved per store manager discretion\"}")))
                    @Valid
                    @RequestBody
                    ApprovePriceOverrideRequest request) {

        String role = resolveReviewerRole();

        PriceOverrideDetail override = priceOverrideService.approveOverride(
                overrideId, new ApproveOverrideCommand(role, request.getComments()));

        return ResponseEntity.ok(override);
    }

    /**
     * Reject a pending price override.
     * Requires order:price_override:reject permission.
     */
    @Operation(
            operationId = "rejectPriceOverride",
            summary = "Reject a Price Override",
            description = """
                    Rejects a PENDING_APPROVAL price override, marking it REJECTED with the supplied reason and \
                    recording a rejection audit row with the reviewer's resolved role.
                    Use this tool to decline a pending override; do not use approvePriceOverride, which grants it \
                    instead.
                    Preconditions: the override must exist and be in PENDING_APPROVAL; a rejected override is \
                    terminal and never touches the order line's price.
                    Required inputs: overrideId (UUID) as a path parameter and reason in the body; comments are \
                    optional, and the reviewer identity and role come from the security context.
                    Emits an ORDER_PRICE_OVERRIDE_REJECT event; no commission-impact fact is published for a \
                    rejection.
                    Returns 404 when the override does not exist, and 422 when the override is not in \
                    PENDING_APPROVAL.
                    """,
            tags = {"Price Overrides"})
    @ApiResponse(
            responseCode = "200",
            description = "Override rejected",
            content = @Content(schema = @Schema(implementation = PriceOverrideDetail.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or override not in pending state")
    @ApiResponse(responseCode = "403", description = "Insufficient rejection permissions")
    @ApiResponse(responseCode = "404", description = "Override not found")
    @PostMapping("/{overrideId}/reject")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PriceOverridePermissions.PRICE_OVERRIDE_REJECT})
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_REJECT + "')")
    @EmitEvent(id = "ORDER_PRICE_OVERRIDE_REJECT", apiVersion = "1")
    public ResponseEntity<PriceOverrideDetail> rejectPriceOverride(
            @Parameter(
                            description = "Price override ID",
                            required = true,
                            example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID overrideId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The rejection reason plus optional reviewer commentary.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rejection with reason", value = """
                                                                    {"reason":"Outside approved discount threshold",
                                                                     "comments":"Discount exceeds policy limit for this product"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RejectPriceOverrideRequest request) {

        String role = resolveReviewerRole();

        PriceOverrideDetail override = priceOverrideService.rejectOverride(
                overrideId, new RejectOverrideCommand(role, request.getReason(), request.getComments()));

        return ResponseEntity.ok(override);
    }

    /**
     * Get a specific price override by ID.
     * Requires order:price_override:view permission.
     */
    @Operation(
            operationId = "getPriceOverride",
            summary = "Get a Price Override",
            description = """
                    Returns one price override with its prices, discount figures, status, audit actors, and \
                    lifecycle timestamps.
                    Use this tool when the override id is already known; use searchPriceOverrides instead to find \
                    overrides by order, status, or date range.
                    Preconditions: the override must exist.
                    Required inputs: overrideId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no price override exists for the supplied id.
                    """,
            tags = {"Price Overrides"})
    @ApiResponse(
            responseCode = "200",
            description = "Override found",
            content = @Content(schema = @Schema(implementation = PriceOverrideDetail.class)))
    @ApiResponse(responseCode = "404", description = "Override not found")
    @GetMapping("/{overrideId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PriceOverridePermissions.PRICE_OVERRIDE_VIEW})
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_VIEW + "')")
    public ResponseEntity<PriceOverrideDetail> getOverride(
            @Parameter(
                            description = "Price override ID",
                            required = true,
                            example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID overrideId) {
        PriceOverrideDetail override = priceOverrideService.getOverrideById(overrideId);
        return ResponseEntity.ok(override);
    }

    /**
     * Get all price overrides for an order.
     * Requires order:price_override:view permission.
     */
    @Operation(
            operationId = "searchPriceOverrides",
            summary = "Search Price Overrides",
            description = """
                    Searches price overrides by order id, status, or creation-date range; exactly one filter \
                    dimension is applied, with orderId taking precedence over status, which takes precedence over \
                    the date range.
                    Use this tool to find overrides matching a filter; use getPriceOverride instead when the \
                    override id is known, or listPendingPriceOverrides for the approval work queue.
                    Preconditions: at least one filter must be supplied, and a date-range search requires both \
                    startDate and endDate.
                    Required inputs: one of orderId (UUID string), status (an override status such as \
                    PENDING_APPROVAL, APPROVED or REJECTED), or startDate plus endDate as ISO-8601 date-times.
                    Emits an ORDER_PRICE_OVERRIDE_SEARCH audit event; no state changes.
                    Returns 200 with a possibly empty list, 400 when no filter is supplied or orderId is not a \
                    UUID, and 422 when status is not a valid override status name.
                    """,
            tags = {"Price Overrides"})
    @ApiResponse(responseCode = "200", description = "Overrides retrieved")
    @ApiResponse(responseCode = "400", description = "No filter parameter provided")
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PriceOverridePermissions.PRICE_OVERRIDE_VIEW})
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_VIEW + "')")
    @EmitEvent(id = "ORDER_PRICE_OVERRIDE_SEARCH", apiVersion = "1")
    public ResponseEntity<List<PriceOverrideDetail>> getOverridesByOrder(
            @Parameter(description = "Order ID filter") @RequestParam(required = false) String orderId,
            @Parameter(description = "Override status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Start date for date range filter")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant startDate,
            @Parameter(description = "End date for date range filter")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant endDate) {

        List<PriceOverrideDetail> overrides;

        if (orderId != null) {
            overrides = priceOverrideService.getOverridesByOrderId(UUID.fromString(orderId));
        } else if (status != null) {
            overrides = priceOverrideService.getOverridesByStatus(status);
        } else if (startDate != null && endDate != null) {
            overrides = priceOverrideService.getOverridesByDateRange(startDate, endDate);
        } else {
            throw new IllegalArgumentException("At least one filter parameter is required");
        }

        return ResponseEntity.ok(overrides);
    }

    /**
     * Get all pending approval overrides.
     * Requires order:price_override:approve permission.
     */
    @Operation(
            operationId = "listPendingPriceOverrides",
            summary = "List Pending Price Override Approvals",
            description = """
                    Lists every price override sitting in PENDING_APPROVAL — the manager approval work queue.
                    Use this tool to fetch what needs a decision; use approvePriceOverride or rejectPriceOverride \
                    to act on an entry, and searchPriceOverrides instead for historical or filtered queries.
                    Preconditions: none beyond the order:price_override:approve permission.
                    Required inputs: none; there are no parameters and no request body.
                    Emits an ORDER_PRICE_OVERRIDE_LIST_PENDING audit event; no state changes.
                    Returns 200 with a possibly empty list; there are no business error conditions beyond \
                    authorization.
                    """,
            tags = {"Price Overrides"})
    @ApiResponse(responseCode = "200", description = "Pending overrides retrieved")
    @GetMapping("/pending")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PriceOverridePermissions.PRICE_OVERRIDE_APPROVE})
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPROVE + "')")
    @EmitEvent(id = "ORDER_PRICE_OVERRIDE_LIST_PENDING", apiVersion = "1")
    public ResponseEntity<List<PriceOverrideDetail>> getPendingApprovals() {
        List<PriceOverrideDetail> overrides = priceOverrideService.getPendingApprovals();
        return ResponseEntity.ok(overrides);
    }

    private String resolveReviewerRole() {
        Set<String> authorities = SecurityContextHelper.getAuthorities();
        return authorities.stream()
                .filter(authority -> authority.startsWith(GatewaySecurityConstants.ROLE_PREFIX))
                .map(authority -> authority.substring(GatewaySecurityConstants.ROLE_PREFIX.length()))
                .sorted()
                .findFirst()
                .orElse(UNKNOWN_REVIEWER_ROLE);
    }
}
