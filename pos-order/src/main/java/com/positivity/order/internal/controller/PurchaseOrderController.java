package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.order.internal.dto.purchaseorder.ProcurementAvailabilityResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.security.PurchaseOrderPermissions;
import com.positivity.order.internal.service.ProcurementAvailabilityService;
import com.positivity.order.internal.service.PurchaseOrderService;
import com.positivity.order.internal.service.PurchaseOrderTransmissionService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders/purchase-orders")
@RequiredArgsConstructor
@Tag(name = "Purchase Orders", description = "Purchase order creation, approval, revision and cancellation")
public class PurchaseOrderController {

    private static final String NO_CURRENT_USER = "No current user";

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderTransmissionService purchaseOrderTransmissionService;
    private final ProcurementAvailabilityService procurementAvailabilityService;

    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:create"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_CREATE + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createPurchaseOrder",
            summary = "Create Purchase Order",
            description = """
                    Creates a DRAFT purchase order with a generated PO number, computing subtotal, tax and grand \
                    total in minor currency units and initialising the open balance to the grand total.
                    Use this tool when ordering stock from a vendor manually; do not use revisePurchaseOrder, \
                    which replaces the lines of an existing order, and do not use convertPurchaseSuggestions, \
                    which turns accepted replenishment suggestions into orders.
                    Preconditions: none; the order starts in DRAFT and must pass approvePurchaseOrder before any \
                    receiving.
                    Required inputs: vendorId (UUID), poDate, currency (ISO 4217) and lines (non-empty), each \
                    naming lineNumber, description and unitCostMinor plus either quantity in base UoM or the \
                    documentUom/documentQuantity pair; tax is derived from the configured default rate.
                    Emits an ORDER_PURCHASE_ORDER_CREATE event; no stock, ledger or vendor-side state is \
                    touched.
                    Returns 400 when a line's quantity is missing without the document-UoM pair, and 422 when a \
                    documentUom has no conversion path to the product's base UoM.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "201",
            description = "Purchase order created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseOrderResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Vendor, commercial terms and the ordered lines for the new DRAFT order.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreatePurchaseOrderRequest.class),
                                            examples = @ExampleObject(name = "Single-line order", value = """
                                                                    {"vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "poDate":"2026-08-13","currency":"USD",
                                                                     "paymentTermsId":"NET30",
                                                                     "expectedDeliveryDate":"2026-08-27",
                                                                     "shipToLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05",
                                                                     "lines":[{"lineNumber":1,
                                                                       "skuId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
                                                                       "description":"Stainless steel water bottle, 750ml",
                                                                       "quantity":12,"unitCostMinor":1499}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreatePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        PurchaseOrderResponse response = purchaseOrderService.createPurchaseOrder(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/{poId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:view"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_VIEW + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_GET", apiVersion = "1")
    @Operation(
            operationId = "getPurchaseOrder",
            summary = "Get Purchase Order",
            description = """
                    Returns one purchase order with its lifecycle status, totals, open balance and lines with open \
                    quantities.
                    Use this tool when the poId is already known; use listPurchaseOrders instead to search by \
                    vendor, status, currency or ship-to location.
                    Preconditions: the purchase order must exist.
                    Required inputs: poId (UUIDv7) path parameter; there is no request body.
                    Emits an ORDER_PURCHASE_ORDER_GET audit event; no stock state changes, this is a \
                    read-only projection.
                    Returns 404 when no purchase order exists for the supplied id.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase order returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseOrderResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrder(
            @Parameter(description = "Purchase order identifier", required = true) @PathVariable UUID poId) {
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrder(poId));
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:view"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_VIEW + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_LIST", apiVersion = "1")
    @Operation(
            operationId = "listPurchaseOrders",
            summary = "List Purchase Orders",
            description = """
                    Returns a page of purchase orders matching the optional filter criteria, with lifecycle \
                    status, totals and open balances.
                    Use this tool to discover a poId or survey open orders; use getPurchaseOrder instead when the \
                    id is already known.
                    Preconditions: none; every filter is optional and an unfiltered call pages over all orders.
                    Required inputs: none; vendorId, status, currency and locationId narrow the result, and \
                    standard page, size and sort parameters control pagination.
                    Emits an ORDER_PURCHASE_ORDER_LIST audit event; no stock state changes, this is a \
                    read-only projection.
                    Returns 200 with an empty page when nothing matches, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(responseCode = "200", description = "Purchase orders returned")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<PurchaseOrderResponse>> listPurchaseOrders(
            @ParameterObject @ModelAttribute ListPurchaseOrdersRequest filter, @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(purchaseOrderService.listPurchaseOrders(filter, pageable));
    }

    @PostMapping("/{poId}/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:approve"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_APPROVE + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_APPROVE", apiVersion = "1")
    @Operation(
            operationId = "approvePurchaseOrder",
            summary = "Approve Purchase Order",
            description = """
                    Approves a DRAFT purchase order, moving it to APPROVED and recording the approver, timestamp \
                    and optional notes.
                    Use this tool to release a drafted order for receiving; do not use cancelPurchaseOrder, which \
                    terminates the order, and note that receivePurchaseOrder rejects orders that were never \
                    approved.
                    Preconditions: the purchase order must exist and be in DRAFT status; any other status is a \
                    conflict.
                    Required inputs: poId (UUIDv7) path parameter and a JSON body where approvalNotes is optional, \
                    so an empty object is acceptable.
                    Emits an ORDER_PURCHASE_ORDER_APPROVE event; the order becomes eligible for ASNs, goods \
                    receipts and receiving sessions.
                    Returns 404 when the purchase order does not exist, and 409 when it is not in DRAFT status.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase order approved",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseOrderResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order approval authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Purchase order is in a conflicting state",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseOrderResponse> approvePurchaseOrder(
            @Parameter(description = "Purchase order identifier", required = true) @PathVariable UUID poId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Approval decision metadata; every field is optional.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ApprovePurchaseOrderRequest.class),
                                            examples = @ExampleObject(name = "Approval with notes", value = """
                                                                    {"approvalNotes":"Approved within budget for Q1 restock"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ApprovePurchaseOrderRequest request) {
        // ADR-0018 deviation: actor resolved in controller following existing
        // pos-inventory module convention.
        // The module pattern extracts actorId in controllers and passes to service
        // layer (see ReceivingController).
        // Full service-layer resolution is tracked as a module-wide refactor for a
        // future ADR update.
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(purchaseOrderService.approvePurchaseOrder(poId, request, actorUserId));
    }

    @PostMapping("/{poId}/revisions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:create"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_CREATE + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_REVISE", apiVersion = "1")
    @Operation(
            operationId = "revisePurchaseOrder",
            summary = "Revise Purchase Order",
            description = """
                    Revises an existing purchase order in place: the version number increments, the header fields \
                    and every line are replaced, totals are recomputed, and the open balance resets to the value \
                    of the new lines.
                    Use this tool to change quantities, costs or delivery terms on an order; do not use \
                    createPurchaseOrder, which starts a new order, and use cancelPurchaseOrder instead when the \
                    order should be terminated rather than changed.
                    Preconditions: the purchase order must exist; the service applies no status gate, so a \
                    revision replaces lines and open balance in any lifecycle status, discarding received-quantity \
                    bookkeeping on the replaced lines.
                    Required inputs: poId (UUIDv7) path parameter plus poDate, revisionReason and the full \
                    replacement lines list, because every line is replaced rather than merged; paymentTermsId, \
                    expectedDeliveryDate, shipToLocationId, requestedBy and comment overwrite the stored values \
                    even when omitted.
                    Emits an ORDER_PURCHASE_ORDER_REVISE event carrying the field-level delta and the \
                    revision reason.
                    Returns 404 when the purchase order does not exist, and 422 when a line's documentUom has no \
                    conversion path to the product's base UoM.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase order revised",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseOrderResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Purchase order is in a conflicting state",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseOrderResponse> revisePurchaseOrder(
            @Parameter(description = "Purchase order identifier", required = true) @PathVariable UUID poId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement header values and the full replacement line set, with the reason"
                                    + " for the revision.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = RevisePurchaseOrderRequest.class),
                                            examples = @ExampleObject(name = "Quantity increase", value = """
                                                                    {"poDate":"2026-08-20",
                                                                     "paymentTermsId":"NET30",
                                                                     "expectedDeliveryDate":"2026-09-03",
                                                                     "revisionReason":"Vendor confirmed availability of larger quantity",
                                                                     "lines":[{"lineNumber":1,
                                                                       "skuId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
                                                                       "description":"Stainless steel water bottle, 750ml",
                                                                       "quantity":24,"unitCostMinor":1399}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RevisePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(purchaseOrderService.revisePurchaseOrder(poId, request, actorUserId));
    }

    @PostMapping("/{poId}/cancel")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:approve"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_APPROVE + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_CANCEL", apiVersion = "1")
    @Operation(
            operationId = "cancelPurchaseOrder",
            summary = "Cancel Purchase Order",
            description = """
                    Cancels a purchase order, moving it to CANCELLED so it can no longer be approved or received.
                    Use this tool when an order is no longer needed; do not use revisePurchaseOrder, which keeps \
                    the order alive with changed lines.
                    Preconditions: the purchase order must exist and must not be FULLY_RECEIVED or CLOSED; any \
                    other status, including DRAFT, may be cancelled.
                    Required inputs: poId (UUIDv7) path parameter; there is no request body and no confirmation \
                    flag.
                    Emits an ORDER_PURCHASE_ORDER_CANCEL event and publishes the order's new state; a cancelled \
                    order is no longer counted as incoming supply by the modules that track it.
                    Returns 404 when the purchase order does not exist, and 409 when it is FULLY_RECEIVED or \
                    CLOSED.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase order cancelled",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseOrderResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order approval authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Purchase order is in a conflicting state",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseOrderResponse> cancelPurchaseOrder(
            @Parameter(description = "Purchase order identifier", required = true) @PathVariable UUID poId) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(purchaseOrderService.cancelPurchaseOrder(poId, actorUserId));
    }

    @PostMapping("/{poId}/transmit")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:transmit"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_TRANSMIT + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_TRANSMIT", apiVersion = "1")
    @Operation(
            operationId = "transmitPurchaseOrder",
            summary = "Transmit Purchase Order To Vendor",
            description = """
                    Sends an approved purchase order to the supplier named on it, asking pos-supplier to \
                    transmit it and report back what the vendor says.
                    Use this tool to actually place an order with a vendor; do not use approvePurchaseOrder for \
                    this, which commits the spend internally and sends nothing to anyone.
                    Preconditions: the order must be APPROVED or PARTIALLY_RECEIVED, must name a supplierRef, \
                    must have no transmission already in flight or awaiting review, and every line must carry an \
                    article code the vendor would recognise.
                    Required inputs: poId (UUIDv7) path parameter; there is no request body, because what is sent \
                    is the order as it stands.
                    Emits an ORDER_PURCHASE_ORDER_TRANSMIT event and queues a supplier order command; the vendor's \
                    answer arrives later and appears on the order's transmission state and timeline.
                    Returns 404 when the purchase order does not exist, and 422 with a code naming the obstacle \
                    when the order cannot be sent: SUPPLIER_REF_MISSING, PURCHASE_ORDER_NOT_APPROVED, \
                    TRANSMISSION_IN_FLIGHT, TRANSMISSION_AWAITING_REVIEW, ARTICLE_NOT_IDENTIFIABLE or \
                    FRACTIONAL_QUANTITY.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(responseCode = "202", description = "Transmission requested")
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The order cannot be transmitted as it stands",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> transmitPurchaseOrder(
            @Parameter(description = "Purchase order id (UUIDv7)", required = true) @PathVariable UUID poId) {
        purchaseOrderTransmissionService.requestTransmission(
                poId, SecurityContextHelper.getCurrentUsername().orElse(NO_CURRENT_USER));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{poId}/supplier-availability")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:availability_view"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_AVAILABILITY_VIEW + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_AVAILABILITY", apiVersion = "1")
    @Operation(
            operationId = "getPurchaseOrderSupplierAvailability",
            summary = "Read Live Vendor Availability For A Purchase Order",
            description = """
                    Asks a vendor what it can supply against every line of a purchase order, in one inquiry, and \
                    returns the answer per line.
                    Use this tool when a buyer is deciding what to order and needs what the vendor says now; do not \
                    use the availability endpoints for this, which report owned stock rather than what a supplier \
                    could send.
                    Preconditions: the purchase order must exist; the vendor profile and delivery location are \
                    both required, because availability is consignee-specific and answering for another location \
                    answers a different question.
                    Required inputs: poId path parameter, and vendorProfileId and deliveryLocationId query \
                    parameters.
                    Emits an ORDER_PURCHASE_ORDER_AVAILABILITY audit event; no state changes, and a supplier that \
                    cannot be reached degrades the answer rather than failing the request.
                    Returns 200 with a document status of SUPPLIER_UNAVAILABLE and no line quantities when the \
                    vendor could not be asked; availableQuantity is null where the vendor stated nothing and zero \
                    only where it stated it has none, and 404 when the purchase order does not exist.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor availability, possibly degraded",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProcurementAvailabilityResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ProcurementAvailabilityResponse> getPurchaseOrderSupplierAvailability(
            @Parameter(description = "Purchase order id (UUIDv7)", required = true) @PathVariable UUID poId,
            @Parameter(description = "Vendor profile to ask", required = true) @RequestParam UUID vendorProfileId,
            @Parameter(description = "Delivery location the answer is for", required = true) @RequestParam
                    UUID deliveryLocationId) {
        return ResponseEntity.ok(
                procurementAvailabilityService.availabilityFor(poId, vendorProfileId, deliveryLocationId));
    }
}
