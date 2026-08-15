package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.DispatchTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.ReceiveTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.ShortCloseTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.TransferOrderService;
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
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * REST controller for cross-site transfer orders (odoo-parity C1, issue #1035).
 *
 * <p>Transfer orders carry stock between different sites through an explicit lifecycle;
 * intra-site bin moves remain on {@code POST /v1/inventory/stock-movements}. The approval step
 * exists only when {@code pos.inventory.transfer.approval-required=true} (decision D-8);
 * approval authority rides on {@code inventory:transfer:dispatch}.
 */
@RestController
@RequestMapping("/v1/inventory/transfer-orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transfer Orders", description = "Cross-site transfer orders with in-transit tracking")
public class TransferOrderController {

    private final TransferOrderService transferOrderService;

    /**
     * Creates a transfer order in DRAFT.
     *
     * @param request the creation request
     * @return the created order
     */
    @PostMapping
    @EmitEvent(id = "INVENTORY_TRANSFER_ORDER_CREATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_CREATE + "')")
    @Operation(
            operationId = "createTransferOrder",
            summary = "Create transfer order",
            description = """
                    Creates a cross-site transfer order in DRAFT that will carry stock between two different sites \
                    through an explicit dispatch/receive lifecycle.
                    Use this tool when stock must move between sites; do not use it for intra-site bin moves, which \
                    post immediately through the stock-movements endpoint, and do not use dispatchTransferOrder, \
                    which ships an order that already exists.
                    Preconditions: source and destination must be different sites, both present in the location \
                    roster with status ACTIVE (DECISION-INVENTORY-009); an optional bin-level storage location must \
                    belong to its site when the storage-location replica knows it.
                    Required inputs: sourceLocationId and destinationLocationId (UUID site ids) and at least one \
                    line with sku and a positive requestedQty; sourceStorageLocationId, destinationStorageLocationId \
                    and notes are optional.
                    Emits an INVENTORY_TRANSFER_ORDER_CREATE event and queues a TransferOrderUpdatedV1 fact; no \
                    stock moves until dispatch.
                    Returns 404 when a site is not in the location roster, 422 with TRANSFER_LOCATION_NOT_ELIGIBLE \
                    when a site is INACTIVE or PENDING, and 400 when source equals destination, a storage location \
                    belongs to another site, lines are empty or a quantity is not positive.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "201", description = "Transfer order created in DRAFT")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request (same source and destination site, empty lines, non-positive quantity,"
                    + " or a storage location outside its site)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:transfer:create",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Source or destination site is not in the location roster",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Source or destination site is not eligible for movement"
                    + " (TRANSFER_LOCATION_NOT_ELIGIBLE: INACTIVE/PENDING per DECISION-INVENTORY-009)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> createTransferOrder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Transfer order to draft: the two sites and the requested SKU lines.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = @ExampleObject(name = "Two-line resupply draft", value = """
                                                                    {"sourceLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "destinationLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "lines":[{"sku":"OIL-5W30-5QT","requestedQty":6},
                                                                              {"sku":"FILTER-PH7317","requestedQty":12}],
                                                                     "notes":"Weekly resupply run"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateTransferOrderRequest request) {
        log.info(
                "Received request to create transfer order {} -> {}",
                request.getSourceLocationId(),
                request.getDestinationLocationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(transferOrderService.createTransferOrder(request));
    }

    /**
     * Retrieves one transfer order.
     *
     * @param transferOrderId the order id
     * @return the order
     */
    @GetMapping("/{transferOrderId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_VIEW + "')")
    @Operation(
            operationId = "getTransferOrder",
            summary = "Get transfer order",
            description = """
                    Returns one transfer order with its lifecycle status, per-line requested, dispatched and \
                    received quantities, pinned lots and any short-close disposition.
                    Use this tool when the transferOrderId is already known; use listTransferOrders instead to \
                    search by status, source or destination site.
                    Preconditions: the transfer order must exist.
                    Required inputs: transferOrderId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no transfer order exists for the supplied id.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "200", description = "Transfer order found")
    @ApiResponse(
            responseCode = "404",
            description = "Transfer order not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> getTransferOrder(
            @Parameter(description = "Transfer order ID", required = true) @PathVariable UUID transferOrderId) {
        return ResponseEntity.ok(transferOrderService.getTransferOrder(transferOrderId));
    }

    /**
     * Lists transfer orders matching the optional filters, newest first.
     *
     * @param status optional lifecycle status filter
     * @param sourceLocationId optional source site filter
     * @param destinationLocationId optional destination site filter
     * @return matching orders
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_VIEW + "')")
    @Operation(
            operationId = "listTransferOrders",
            summary = "List transfer orders",
            description = """
                    Lists transfer orders newest first, optionally filtered by lifecycle status, source site and \
                    destination site.
                    Use this tool to discover transferOrderIds or survey in-flight transfers; use getTransferOrder \
                    instead when the id is already known.
                    Preconditions: none; every filter is optional and omitted filters match everything.
                    Required inputs: none; status (TransferOrderStatus), sourceLocationId and destinationLocationId \
                    are optional query parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when nothing matches, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Transfer orders retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = TransferOrderResponse.class))))
    public ResponseEntity<List<TransferOrderResponse>> listTransferOrders(
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    TransferOrderStatus status,
            @Parameter(description = "Filter by source site") @RequestParam(required = false) UUID sourceLocationId,
            @Parameter(description = "Filter by destination site") @RequestParam(required = false)
                    UUID destinationLocationId) {
        return ResponseEntity.ok(
                transferOrderService.listTransferOrders(status, sourceLocationId, destinationLocationId));
    }

    /**
     * Approves a DRAFT transfer order (only when the approval flag is enabled).
     *
     * @param transferOrderId the order id
     * @return the approved order
     */
    @PostMapping("/{transferOrderId}/approve")
    @EmitEvent(id = "INVENTORY_TRANSFER_ORDER_APPROVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:dispatch"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_DISPATCH + "')")
    @Operation(
            operationId = "approveTransferOrder",
            summary = "Approve transfer order",
            description = """
                    Approves a DRAFT transfer order, moving it to APPROVED so it becomes dispatchable when the \
                    approval step is enabled (decision D-8).
                    Use this tool only when the deployment sets pos.inventory.transfer.approval-required=true; do \
                    not use dispatchTransferOrder to approve, and with the flag off (the default) skip approval \
                    entirely because DRAFT orders dispatch directly and this call returns 409.
                    Preconditions: the approval flag must be on and the order must be in DRAFT.
                    Required inputs: transferOrderId (UUID) as a path parameter; there is no request body.
                    Emits an INVENTORY_TRANSFER_ORDER_APPROVE event and queues a TransferOrderUpdatedV1 fact; no \
                    stock moves.
                    Returns 404 when the order does not exist, and 409 when the approval step is disabled or the \
                    order is not in DRAFT.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "200", description = "Transfer order approved")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:transfer:dispatch",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Transfer order not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Approval step disabled, or order is not in DRAFT",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> approveTransferOrder(
            @Parameter(description = "Transfer order ID", required = true) @PathVariable UUID transferOrderId) {
        log.info("Received request to approve transfer order {}", transferOrderId);
        return ResponseEntity.ok(transferOrderService.approveTransferOrder(transferOrderId));
    }

    /**
     * Dispatches a transfer order from the source site, posting TRANSFER_OUT into transit.
     *
     * @param transferOrderId the order id
     * @param request optional per-line dispatch quantities (omitted lines dispatch full requested)
     * @return the dispatched order
     */
    @PostMapping("/{transferOrderId}/dispatch")
    @EmitEvent(id = "INVENTORY_TRANSFER_ORDER_DISPATCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:dispatch"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_DISPATCH + "')")
    @Operation(
            operationId = "dispatchTransferOrder",
            summary = "Dispatch transfer order",
            description = """
                    Dispatches a transfer order from the source site, posting one TRANSFER_OUT per line through the \
                    ledger funnel that decrements source on-hand and raises the destination in-transit quantity.
                    Use this tool when the stock physically leaves the source; do not use receiveTransferOrder, \
                    which books arrival at the destination, and do not use createTransferOrder, which only drafts \
                    the order.
                    Preconditions: the order must be in DRAFT (or APPROVED when \
                    pos.inventory.transfer.approval-required=true), both sites must still be ACTIVE for movement, \
                    source on-hand must cover each dispatched quantity, and each LOT-tracked SKU must name an \
                    existing ACTIVE lot, which is pinned on the line for receive and short-close.
                    Required inputs: transferOrderId (UUID) path parameter; the body is optional — lines pin a \
                    per-line quantity (at most the requested quantity) and a lotNumber for LOT-tracked SKUs, and \
                    omitted lines dispatch their full requested quantity.
                    Emits an INVENTORY_TRANSFER_ORDER_DISPATCH event, posts the ledger batch with \
                    sourceTransactionId = transferOrderId and queues a TransferOrderUpdatedV1 fact.
                    Returns 404 when the order does not exist, 409 when it is not dispatchable, 422 with \
                    TRANSFER_DISPATCH_EXCEEDS_REQUESTED, INSUFFICIENT_STOCK, TRANSFER_LOCATION_NOT_ELIGIBLE, \
                    LOT_NUMBER_REQUIRED, LOT_UNKNOWN or LOT_NOT_AVAILABLE, and 400 for an unknown or duplicate \
                    line id.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "200", description = "Transfer order dispatched")
    @ApiResponse(
            responseCode = "400",
            description = "Unknown or duplicate line id in the request",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:transfer:dispatch",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Transfer order not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Order is not dispatchable (already dispatched/terminal, or awaiting approval)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Dispatch quantity exceeds requested (TRANSFER_DISPATCH_EXCEEDS_REQUESTED), source"
                    + " on-hand insufficient (INSUFFICIENT_STOCK — TRANSFER_OUT is blocked below zero), or a"
                    + " site is not movement-eligible (TRANSFER_LOCATION_NOT_ELIGIBLE)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> dispatchTransferOrder(
            @Parameter(description = "Transfer order ID", required = true) @PathVariable UUID transferOrderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional per-line dispatch quantities and lot numbers; omitted lines"
                                    + " dispatch their full requested quantity.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples =
                                                    @ExampleObject(
                                                            name = "Partial dispatch of one lot-tracked line",
                                                            value = """
                                                                    {"lines":[{"lineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
                                                                               "quantity":4,"lotNumber":"LOT-2026-A"}]}
                                                                    """)))
                    @Valid
                    @RequestBody(required = false)
                    DispatchTransferOrderRequest request) {
        log.info("Received request to dispatch transfer order {}", transferOrderId);
        return ResponseEntity.ok(transferOrderService.dispatchTransferOrder(
                transferOrderId, request != null ? request : new DispatchTransferOrderRequest()));
    }

    /**
     * Receives dispatched quantities of a transfer order at the destination site.
     *
     * @param transferOrderId the order id
     * @param request optional per-line received quantities (omitted lines receive full remainder)
     * @return the updated order
     */
    @PostMapping("/{transferOrderId}/receive")
    @EmitEvent(id = "INVENTORY_TRANSFER_ORDER_RECEIVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:receive"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_RECEIVE + "')")
    @Operation(
            operationId = "receiveTransferOrder",
            summary = "Receive transfer order",
            description = """
                    Receives dispatched quantities at the destination site, posting one TRANSFER_IN per line through \
                    the ledger funnel that raises destination on-hand and drains the in-transit quantity.
                    Use this tool when goods arrive at the destination; do not use dispatchTransferOrder, which \
                    ships from the source, and use shortCloseTransferOrder instead when the remainder will never \
                    arrive.
                    Preconditions: the order must be DISPATCHED or PARTIALLY_RECEIVED and the destination site must \
                    still be ACTIVE for movement; each line receives against the lot pinned at dispatch, never a \
                    request-keyed one.
                    Required inputs: transferOrderId (UUID) path parameter; the body is optional — lines pin a \
                    per-line quantity (at most dispatched minus already received), and omitted lines receive their \
                    full un-received remainder.
                    Emits an INVENTORY_TRANSFER_ORDER_RECEIVE event and queues a TransferOrderUpdatedV1 fact; the \
                    status becomes RECEIVED when every line is fully received, else PARTIALLY_RECEIVED with the \
                    remainder still in transit.
                    Returns 404 when the order does not exist, 409 when it is not receivable, 422 with \
                    TRANSFER_RECEIVE_EXCEEDS_DISPATCHED or TRANSFER_LOCATION_NOT_ELIGIBLE, and 400 for an unknown \
                    or duplicate line id.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "200", description = "Receipt recorded (RECEIVED or PARTIALLY_RECEIVED)")
    @ApiResponse(
            responseCode = "400",
            description = "Unknown or duplicate line id in the request",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:transfer:receive",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Transfer order not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Order is not receivable (not yet dispatched, cancelled, short-closed, or already"
                    + " fully received)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Received quantity exceeds the un-received dispatched remainder"
                    + " (TRANSFER_RECEIVE_EXCEEDS_DISPATCHED), or the destination site is not movement-eligible"
                    + " (TRANSFER_LOCATION_NOT_ELIGIBLE)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> receiveTransferOrder(
            @Parameter(description = "Transfer order ID", required = true) @PathVariable UUID transferOrderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional per-line received quantities; omitted lines receive their full"
                                    + " un-received remainder.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples =
                                                    @ExampleObject(name = "Partial receipt of one line", value = """
                                                                    {"lines":[{"lineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
                                                                               "quantity":4}]}
                                                                    """)))
                    @Valid
                    @RequestBody(required = false)
                    ReceiveTransferOrderRequest request) {
        log.info("Received request to receive transfer order {}", transferOrderId);
        return ResponseEntity.ok(transferOrderService.receiveTransferOrder(
                transferOrderId, request != null ? request : new ReceiveTransferOrderRequest()));
    }

    /**
     * Short-closes a transfer order with an undelivered in-transit remainder.
     *
     * @param transferOrderId the order id
     * @param request the mandatory disposition, reason, and notes
     * @return the short-closed order
     */
    @PostMapping("/{transferOrderId}/short-close")
    @EmitEvent(id = "INVENTORY_TRANSFER_ORDER_SHORT_CLOSE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:short_close"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_SHORT_CLOSE + "')")
    @Operation(
            operationId = "shortCloseTransferOrder",
            summary = "Short-close transfer order",
            description = """
                    Short-closes a transfer order whose in-transit remainder will never be received normally, \
                    resolving every line's dispatched-minus-received quantity with one whole-order disposition.
                    Use this tool for stock lost or turned back in transit; do not use cancelTransferOrder, which \
                    only works before dispatch, and do not use receiveTransferOrder, which books a normal arrival.
                    Preconditions: the order must be DISPATCHED or PARTIALLY_RECEIVED with a positive in-transit \
                    remainder; for RETURNED_TO_SOURCE the source site must still be ACTIVE for movement, while \
                    LOST_IN_TRANSIT deliberately skips the eligibility check so a loss can be resolved toward a \
                    deactivated destination.
                    Required inputs: transferOrderId (UUID) path parameter plus a body with disposition \
                    (LOST_IN_TRANSIT writes the remainder off via a paired constructive TRANSFER_IN and SCRAP_OUT \
                    with reason LOST at the destination; RETURNED_TO_SOURCE restores it to source on-hand), and \
                    mandatory reason and notes.
                    Emits an INVENTORY_TRANSFER_ORDER_SHORT_CLOSE event; all ledger entries post atomically with \
                    sourceTransactionId = transferOrderId, destination in-transit zeroes, the order reaches the \
                    terminal SHORT_CLOSED status, and the TransferOrderUpdatedV1 fact carries the disposition — no \
                    scrap document is created.
                    Returns 404 when the order does not exist, 409 when it is not short-closable or has no \
                    outstanding remainder, 422 with TRANSFER_LOCATION_NOT_ELIGIBLE for RETURNED_TO_SOURCE toward an \
                    ineligible source, and 400 when disposition, reason or notes are missing.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "200", description = "Transfer order short-closed (terminal)")
    @ApiResponse(
            responseCode = "400",
            description = "Missing disposition, reason, or notes",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:transfer:short_close",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Transfer order not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Order is not short-closable (not DISPATCHED/PARTIALLY_RECEIVED, or no outstanding"
                    + " in-transit remainder)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "RETURNED_TO_SOURCE only: the source site is not movement-eligible"
                    + " (TRANSFER_LOCATION_NOT_ELIGIBLE per DECISION-INVENTORY-009)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> shortCloseTransferOrder(
            @Parameter(description = "Transfer order ID", required = true) @PathVariable UUID transferOrderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Whole-order disposition of the undelivered remainder with the mandatory"
                                    + " audit reason and notes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples =
                                                    @ExampleObject(name = "Write off a lost remainder", value = """
                                                                    {"disposition":"LOST_IN_TRANSIT",
                                                                     "reason":"Carrier loss claim",
                                                                     "notes":"Carrier confirmed the pallet missing; claim CL-2291 filed"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ShortCloseTransferOrderRequest request) {
        log.info(
                "Received request to short-close transfer order {} with disposition {}",
                transferOrderId,
                request.getDisposition());
        return ResponseEntity.ok(transferOrderService.shortCloseTransferOrder(transferOrderId, request));
    }

    /**
     * Cancels a transfer order before dispatch.
     *
     * @param transferOrderId the order id
     * @return the cancelled order
     */
    @PostMapping("/{transferOrderId}/cancel")
    @EmitEvent(id = "INVENTORY_TRANSFER_ORDER_CANCEL", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:transfer:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.TRANSFER_CREATE + "')")
    @Operation(
            operationId = "cancelTransferOrder",
            summary = "Cancel transfer order",
            description = """
                    Cancels a transfer order before dispatch, moving DRAFT or APPROVED to the terminal CANCELLED \
                    status.
                    Use this tool to abandon an order whose stock has not moved; do not use it after dispatch — the \
                    stock has physically moved, so resolve discrepancies with shortCloseTransferOrder instead.
                    Preconditions: the order must be in DRAFT or APPROVED.
                    Required inputs: transferOrderId (UUID) as a path parameter; there is no request body.
                    Emits an INVENTORY_TRANSFER_ORDER_CANCEL event and queues a TransferOrderUpdatedV1 fact; no \
                    ledger entries are posted.
                    Returns 404 when the order does not exist, and 409 when it has already been dispatched or is \
                    terminal.
                    """,
            tags = {"Transfer Orders"})
    @ApiResponse(responseCode = "200", description = "Transfer order cancelled")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:transfer:create",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Transfer order not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Order already dispatched (or terminal) — cancellation is only allowed before dispatch",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransferOrderResponse> cancelTransferOrder(
            @Parameter(description = "Transfer order ID", required = true) @PathVariable UUID transferOrderId) {
        log.info("Received request to cancel transfer order {}", transferOrderId);
        return ResponseEntity.ok(transferOrderService.cancelTransferOrder(transferOrderId));
    }
}
