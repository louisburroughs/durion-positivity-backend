package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.AddItemRequest;
import com.positivity.order.internal.dto.CheckoutRequest;
import com.positivity.order.internal.dto.CreateCartRequest;
import com.positivity.order.internal.dto.LinkSourceRequest;
import com.positivity.order.internal.dto.OrderDiscountRequest;
import com.positivity.order.internal.dto.SalesOrderLineResponse;
import com.positivity.order.internal.dto.SalesOrderResponse;
import com.positivity.order.internal.dto.UpdateItemRequest;
import com.positivity.order.internal.dto.VoidOrderRequest;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.SalesOrderService;
import com.positivity.order.internal.service.model.AddItemCommand;
import com.positivity.order.internal.service.model.CheckoutResult;
import com.positivity.order.internal.service.model.CreateCartCommand;
import com.positivity.order.internal.service.model.CreateCartResult;
import com.positivity.order.internal.service.model.OrderDiscountCommand;
import com.positivity.order.internal.service.model.SalesOrderLineSummary;
import com.positivity.order.internal.service.model.SalesOrderSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
@Tag(name = "Sales Orders", description = "Sales order cart management")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    @Operation(
            operationId = "createCart",
            summary = "Create a Sales Order Cart",
            description = """
                    Creates a DRAFT sales order cart bound to a clerk and terminal, with optional customer, vehicle, \
                    and deposit-source context; a register session open on the terminal binds the cart to it and \
                    supplies the location when locationId is omitted.
                    Use this tool to start a new sale at the counter; do not use addCartItem, which adds line items \
                    to a cart that already exists.
                    Preconditions: the terminal must not have a register session in CLOSING, the customer and \
                    vehicle must exist in CRM when supplied, and a location must be resolvable from the request or \
                    the open session.
                    Required inputs: clerkId and terminalId; customerId and vehicleId are optional UUID strings, \
                    depositSourceType (ESTIMATE, WORKORDER or ORDER) and depositSourceId must be supplied together, \
                    and the optional Idempotency-Key header makes creation replay-safe.
                    Emits an ORDER_CART_CREATE event and records the initial DRAFT status-history row.
                    Returns 201 on creation, 200 when a replayed Idempotency-Key returns the original cart, 400 \
                    when locationId cannot be resolved or depositSourceType/depositSourceId is only half supplied, \
                    409 when the key was previously used with a different payload, and 422 when the customer or \
                    vehicle cannot be validated or the terminal's session is being closed.
                    """,
            tags = {"Sales Orders"})
    @PostMapping("/carts")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CREATE + "')")
    @EmitEvent(id = "ORDER_CART_CREATE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> createCart(
            @Parameter(description = "Client idempotency key; replays return the original cart")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cart-creation context: who is selling, where, and for whom.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Counter cart with customer", value = """
                                                                    {"clerkId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a50",
                                                                     "terminalId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a90",
                                                                     "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70",
                                                                     "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a80",
                                                                     "label":"blue F-150 waiting on customer"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateCartRequest request) {
        CreateCartResult result = salesOrderService.createCart(new CreateCartCommand(
                request.getClerkId(),
                request.getTerminalId(),
                request.getCustomerId(),
                request.getVehicleId(),
                request.getLocationId(),
                request.getLabel(),
                request.getGeneralNote(),
                idempotencyKey,
                request.getDepositSourceType(),
                request.getDepositSourceId()));
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(toResponse(result.summary()));
    }

    @Operation(
            operationId = "listCarts",
            summary = "List Sales Order Carts",
            description = """
                    Lists sales order carts filtered by clerk, terminal, and status — the draft-parking and resume \
                    surface; line items are omitted from list results.
                    Use this tool when searching for carts to resume or review; use getOrder instead when the order \
                    id is already known and full line detail is needed.
                    Preconditions: none beyond an authenticated caller with order view permission.
                    Required inputs: none — clerkId, terminalId, and status (a status name such as DRAFT) are \
                    optional filters; page defaults to 0 and size defaults to 20, capped at 100.
                    Emits an ORDER_CART_LIST audit event; no order state changes.
                    Returns 200 with a possibly empty page, and 400 when status is not a valid order status name.
                    """,
            tags = {"Sales Orders"})
    @GetMapping("/carts")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_VIEW + "')")
    @EmitEvent(id = "ORDER_CART_LIST", apiVersion = "1")
    public ResponseEntity<List<SalesOrderResponse>> listCarts(
            @Parameter(description = "Filter by clerk identifier") @RequestParam(required = false) String clerkId,
            @Parameter(description = "Filter by terminal identifier") @RequestParam(required = false) String terminalId,
            @Parameter(description = "Filter by order status, e.g. DRAFT") @RequestParam(required = false)
                    String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<SalesOrderResponse> carts = salesOrderService.listCarts(clerkId, terminalId, status, page, size).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(carts);
    }

    @Operation(
            operationId = "addCartItem",
            summary = "Add an Item to a Cart",
            description = """
                    Adds a line item to a DRAFT sales order cart, pricing it through the pricing service unless a \
                    permissioned manual price is supplied, and marking the line AVAILABLE or BACKORDER from an \
                    inventory availability check.
                    Use this tool to put a SKU on an existing cart; do not use updateCartItemQuantity, which only \
                    changes the quantity of a line already on the cart.
                    Preconditions: the order must exist and be DRAFT, and a manual price requires the \
                    order:line:enter_manual_price permission.
                    Required inputs: itemSku and quantity (minimum 1); manualPrice, reasonCode, notes, and \
                    serialNumbers (never more than quantity) are optional, and a client-supplied lineUuid makes the \
                    call replay-safe — a replay with a known lineUuid updates that line's quantity instead of \
                    duplicating it.
                    Emits an ORDER_CART_ITEM_ADD event, recomputes order totals, and marks tax stale.
                    Returns 400 when the SKU is unknown, 404 when the order does not exist, 409 when the order is \
                    not DRAFT or the lineUuid was previously used for a different SKU, and 422 when pricing is \
                    unavailable or serialNumbers exceed the quantity.
                    """,
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/items")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_LINE_CREATE + "')")
    @EmitEvent(id = "ORDER_CART_ITEM_ADD", apiVersion = "1")
    public ResponseEntity<SalesOrderLineResponse> addItem(
            @PathVariable UUID orderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The line item to add: SKU, quantity, and optional pricing context.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Priced line with replay identity",
                                                            value = """
                                                                    {"itemSku":"SKU-OIL-5W30-1L",
                                                                     "quantity":2,
                                                                     "lineUuid":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aaa",
                                                                     "customerNote":"Front wipers only"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AddItemRequest request) {
        SalesOrderLineSummary line = salesOrderService.addItem(
                orderId,
                new AddItemCommand(
                        request.getItemSku(),
                        request.getQuantity(),
                        request.getReasonCode(),
                        request.getManualPrice(),
                        request.getLineUuid(),
                        request.getCustomerNote(),
                        request.getInternalNote(),
                        request.getSerialNumbers()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toLineResponse(line));
    }

    @Operation(
            operationId = "updateCartItemQuantity",
            summary = "Update a Cart Item Quantity",
            description = """
                    Updates the quantity of an existing line on a DRAFT sales order cart and recomputes order totals.
                    Use this tool to change how many units a line sells; do not use removeCartItem, which deletes \
                    the line entirely, and do not use addCartItem, which creates a new line.
                    Preconditions: the order must exist and be DRAFT, and the line must exist on that order.
                    Required inputs: orderId and lineId as path UUIDs, and quantity (minimum 1) in the body.
                    Emits an ORDER_CART_ITEM_UPDATE event, recomputes order totals, and marks tax stale.
                    Returns 404 when the order or line does not exist, and 409 when the order is not DRAFT.
                    """,
            tags = {"Sales Orders"})
    @PutMapping("/carts/{orderId}/items/{lineId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_LINE_EDIT + "')")
    @EmitEvent(id = "ORDER_CART_ITEM_UPDATE", apiVersion = "1")
    public ResponseEntity<SalesOrderLineResponse> updateItemQuantity(
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The new quantity for the line.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Set quantity", value = "{\"quantity\":3}")))
                    @Valid
                    @RequestBody
                    UpdateItemRequest request) {
        SalesOrderLineSummary line = salesOrderService.updateItemQuantity(orderId, lineId, request.getQuantity());
        return ResponseEntity.ok(toLineResponse(line));
    }

    @Operation(
            operationId = "removeCartItem",
            summary = "Remove an Item from a Cart",
            description = """
                    Removes a line item from a DRAFT sales order cart and recomputes the remaining totals.
                    Use this tool to take a line off the sale entirely; do not use updateCartItemQuantity, which \
                    keeps the line and changes its quantity.
                    Preconditions: the order must exist and be DRAFT; a lineId that is not on the order is silently \
                    ignored rather than rejected.
                    Required inputs: orderId and lineId as path UUIDs; there is no request body.
                    Emits an ORDER_CART_ITEM_REMOVE event, recomputes order totals, and marks tax stale.
                    Returns 204 on success, 404 when the order does not exist, and 409 when the order is not DRAFT.
                    """,
            tags = {"Sales Orders"})
    @DeleteMapping("/carts/{orderId}/items/{lineId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_LINE_DELETE + "')")
    @EmitEvent(id = "ORDER_CART_ITEM_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> removeItem(@PathVariable UUID orderId, @PathVariable UUID lineId) {
        salesOrderService.removeItem(orderId, lineId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "getOrder",
            summary = "Get a Sales Order by Id",
            description = """
                    Returns a sales order with its current line items, totals, status, invoice references, and \
                    payment balances.
                    Use this tool when the order id is already known; use listCarts instead to search by clerk, \
                    terminal or status.
                    Preconditions: the order must exist.
                    Required inputs: orderId (UUID) as a path parameter; there is no request body and no filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no sales order exists for the supplied id.
                    """,
            tags = {"Sales Orders"})
    @GetMapping("/carts/{orderId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_VIEW + "')")
    public ResponseEntity<SalesOrderResponse> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.getOrder(orderId)));
    }

    @Operation(
            operationId = "applyOrderDiscount",
            summary = "Apply an Order-Level Discount",
            description = """
                    Applies or replaces the single order-level discount on a DRAFT cart, allocated pro-rata across \
                    lines when totals are recomputed.
                    Use this tool for a whole-order concession; do not use applyPriceOverride, which changes one \
                    line's unit price through the price-override approval workflow.
                    Preconditions: the order must exist and be DRAFT.
                    Required inputs: type (PERCENT or AMOUNT) and a positive value — a PERCENT value must not \
                    exceed 100; reasonCode is optional.
                    Emits an ORDER_CART_DISCOUNT_APPLY event, recomputes order totals, and marks tax stale.
                    Returns 404 when the order does not exist, 409 when the order is not DRAFT, and 422 when the \
                    type or value is invalid.
                    """,
            tags = {"Sales Orders"})
    @PutMapping("/carts/{orderId}/discount")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_DISCOUNT + "')")
    @EmitEvent(id = "ORDER_CART_DISCOUNT_APPLY", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> applyOrderDiscount(
            @PathVariable UUID orderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The order-level discount to set, replacing any existing one.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Ten percent goodwill", value = """
                                                                    {"type":"PERCENT","value":10,
                                                                     "reasonCode":"GOODWILL_ADJUSTMENT"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    OrderDiscountRequest request) {
        SalesOrderSummary updated = salesOrderService.applyOrderDiscount(
                orderId, new OrderDiscountCommand(request.getType(), request.getValue(), request.getReasonCode()));
        return ResponseEntity.ok(toResponse(updated));
    }

    @Operation(
            operationId = "clearOrderDiscount",
            summary = "Remove the Order-Level Discount",
            description = """
                    Clears any order-level discount from a DRAFT cart and recomputes totals.
                    Use this tool to withdraw a whole-order concession; do not use applyOrderDiscount, which sets \
                    or replaces the discount.
                    Preconditions: the order must exist and be DRAFT; clearing a cart that has no discount succeeds.
                    Required inputs: orderId (UUID) as a path parameter; there is no request body.
                    Emits an ORDER_CART_DISCOUNT_REMOVE event, recomputes order totals, and marks tax stale.
                    Returns 404 when the order does not exist, and 409 when the order is not DRAFT.
                    """,
            tags = {"Sales Orders"})
    @DeleteMapping("/carts/{orderId}/discount")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_DISCOUNT + "')")
    @EmitEvent(id = "ORDER_CART_DISCOUNT_REMOVE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> clearOrderDiscount(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.clearOrderDiscount(orderId)));
    }

    @Operation(
            operationId = "quoteCart",
            summary = "Convert a Cart to a Counter Quote",
            description = """
                    Converts a DRAFT counter cart to QUOTED by running a final reprice and authoritative tax \
                    computation and stamping a validity horizon (default P7D, configurable via \
                    pos.order.quote.validity).
                    Use this tool to hand the customer a priced, resumable counter quote; do not use \
                    checkoutOrder, which freezes the cart for payment, and note that workorder-linked orders are \
                    quoted via pos-workorder estimates and are rejected here.
                    Preconditions: the order must be DRAFT with at least one line, must not reference a workorder \
                    directly or through imported lines, and pricing must be reachable for every non-manual, \
                    non-source line.
                    Required inputs: orderId (UUID) as a path parameter; there is no request body.
                    Emits an ORDER_CART_QUOTE event.
                    Returns 404 when the order does not exist, 409 when the status does not allow the transition, \
                    422 when the cart is empty, workorder-linked, or pricing is unavailable, and 503 when the tax \
                    service cannot be reached.
                    """,
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/quote")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_QUOTE + "')")
    @EmitEvent(id = "ORDER_CART_QUOTE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> quote(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.quote(orderId)));
    }

    @Operation(
            operationId = "reopenQuote",
            summary = "Reopen a Counter Quote",
            description = """
                    Reopens a QUOTED counter quote back to DRAFT, clearing the validity horizon so the cart can be \
                    edited again.
                    Use this tool to resume editing a quoted cart; do not use quoteCart, which moves the cart in \
                    the opposite direction from DRAFT to QUOTED.
                    Preconditions: the order must be QUOTED.
                    Required inputs: orderId (UUID) as a path parameter; there is no request body.
                    Emits an ORDER_CART_QUOTE_REOPEN event and best-effort reprices the lines — an unavailable \
                    pricing service keeps the old prices, and totals are recomputed with tax marked stale.
                    Returns 404 when the order does not exist, and 409 when the order is not QUOTED.
                    """,
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/quote/reopen")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_QUOTE + "')")
    @EmitEvent(id = "ORDER_CART_QUOTE_REOPEN", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> reopenQuote(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.reopenQuote(orderId)));
    }

    @Operation(
            operationId = "checkoutOrder",
            summary = "Check Out a Sales Order",
            description = """
                    Freezes a DRAFT or QUOTED cart into PENDING_PAYMENT: revalidates availability and serial \
                    capture, runs the final reprice and tax computation, and synchronously creates the fronting \
                    invoice at pos-invoice, rolling the whole checkout back if invoice creation fails.
                    Use this tool when the customer is ready to pay; do not use quoteCart, which produces a \
                    resumable quote, and do not use voidOrder, which abandons an order already in PENDING_PAYMENT.
                    Preconditions: the cart must be non-empty with customer validation not PENDING, every line must \
                    have sufficient inventory, serial-tracked lines must carry one serial per unit (lot-tracked at \
                    least one), and ON_ACCOUNT additionally requires the order:order:charge_on_account permission \
                    and a VALIDATED commercial customer with payment terms and no credit hold.
                    Required inputs: the Idempotency-Key header; the body is optional with tenderType DEFAULT or \
                    ON_ACCOUNT — DEFAULT settles asynchronously via payment events that complete the order when \
                    the balance reaches zero, while ON_ACCOUNT settles against the AR invoice and completes the \
                    order immediately.
                    Emits an ORDER_CHECKOUT event; an ON_ACCOUNT checkout also records a settled ON_ACCOUNT ledger \
                    entry and publishes an order-completed fact.
                    Returns 201 on checkout, 200 when the same Idempotency-Key replays the checked-out order, 400 \
                    when the Idempotency-Key header is blank or tenderType is unsupported, 409 when the key belongs \
                    to a different order or the status does not allow checkout, 422 when the cart is empty, \
                    customer validation is pending, availability or serial capture is insufficient, or on-account \
                    eligibility fails, and 503 when the tax or invoicing service is unreachable.
                    """,
            tags = {"Sales Orders"})
    @PostMapping("/{orderId}/checkout")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CHECKOUT + "')")
    @EmitEvent(id = "ORDER_CHECKOUT", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> checkout(
            @PathVariable UUID orderId,
            @Parameter(description = "Required checkout idempotency key; replays return the original result")
                    @RequestHeader(name = "Idempotency-Key")
                    String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional checkout options; omit the body entirely for default tender.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "On-account tender",
                                                            value = "{\"tenderType\":\"ON_ACCOUNT\"}")))
                    @RequestBody(required = false)
                    CheckoutRequest request) {
        CheckoutResult result =
                salesOrderService.checkout(orderId, idempotencyKey, request == null ? null : request.getTenderType());
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(toResponse(result.summary()));
    }

    @Operation(
            operationId = "voidOrder",
            summary = "Void an Unsettled Order",
            description = """
                    Voids a PENDING_PAYMENT order before any settlement: cancels the fronting pos-invoice invoice \
                    and transitions the order to VOIDED, rolling the void back if the invoice cancel fails.
                    Use this tool to abandon a checked-out order that has taken no money; do not use cancelOrder, \
                    which runs the cancellation saga for DRAFT and QUOTED orders and reverses settled payments.
                    Preconditions: the order must be PENDING_PAYMENT with no settled payment records; voiding an \
                    already VOIDED order is an idempotent no-op.
                    Required inputs: orderId (UUID) as a path parameter; the body is optional and carries only a \
                    free-text reason.
                    Emits an ORDER_VOID event.
                    Returns 200 on success or an already-voided no-op, 404 when the order does not exist, 409 when \
                    settled payments exist (route through cancelOrder) or the status is not PENDING_PAYMENT, and \
                    503 when the invoicing service is unreachable.
                    """,
            tags = {"Sales Orders"})
    @PostMapping("/{orderId}/void")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_VOID + "')")
    @EmitEvent(id = "ORDER_VOID", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> voidOrder(
            @PathVariable UUID orderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional void context; the body may be omitted entirely.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Void with reason",
                                                            value =
                                                                    "{\"reason\":\"Customer walked away before payment\"}")))
                    @RequestBody(required = false)
                    VoidOrderRequest request) {
        SalesOrderSummary voided = salesOrderService.voidOrder(orderId, request == null ? null : request.getReason());
        return ResponseEntity.ok(toResponse(voided));
    }

    @Operation(
            operationId = "linkOrderSource",
            summary = "Link a Source Document to an Order",
            description = """
                    Imports the line items of a source document (an ESTIMATE or WORKORDER) into a DRAFT cart, \
                    merging into same-SKU same-price lines where possible; imported source prices are contractual \
                    and are never repriced.
                    Use this tool to pull approved estimate or workorder lines onto a sale; do not use addCartItem, \
                    which adds individually priced counter lines.
                    Preconditions: the order must be DRAFT, a WORKORDER source requires a customer already on the \
                    cart, and source lines already linked to the cart are skipped, making the call replay-safe.
                    Required inputs: sourceType (ESTIMATE or WORKORDER) and sourceId, both in the body.
                    Emits an ORDER_LINK_SOURCE event, recomputes order totals, and marks tax stale.
                    Returns 404 when the order does not exist, 400 when sourceType is unknown, 409 when the order \
                    is not DRAFT, and 422 when a WORKORDER link is attempted without a customer on the cart.
                    """,
            tags = {"Sales Orders"})
    @PatchMapping("/carts/{orderId}/source")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_EDIT + "')")
    @EmitEvent(id = "ORDER_LINK_SOURCE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> linkSource(
            @PathVariable UUID orderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The source document whose lines are imported into the cart.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Workorder link", value = """
                                                                    {"sourceType":"WORKORDER",
                                                                     "sourceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    LinkSourceRequest request) {
        SalesOrderSummary updated =
                salesOrderService.linkSource(orderId, request.getSourceType(), request.getSourceId());
        return ResponseEntity.ok(toResponse(updated));
    }

    private SalesOrderResponse toResponse(SalesOrderSummary summary) {
        List<SalesOrderLineResponse> lines =
                summary.lines().stream().map(this::toLineResponse).toList();
        return SalesOrderResponse.builder()
                .orderId(summary.orderId())
                .orderNumber(summary.orderNumber())
                .locationId(summary.locationId())
                .label(summary.label())
                .customerId(summary.customerId())
                .vehicleId(summary.vehicleId())
                .customerValidationStatus(summary.customerValidationStatus())
                .clerkId(summary.clerkId())
                .terminalId(summary.terminalId())
                .status(summary.status())
                .subtotal(summary.subtotal())
                .discountTotal(summary.discountTotal())
                .taxTotal(summary.taxTotal())
                .grandTotal(summary.grandTotal())
                .taxStale(summary.taxStale())
                .orderDiscountType(summary.orderDiscountType())
                .orderDiscountValue(summary.orderDiscountValue())
                .orderDiscountReasonCode(summary.orderDiscountReasonCode())
                .generalNote(summary.generalNote())
                .quoteExpiresAt(summary.quoteExpiresAt())
                .invoiceId(summary.invoiceId())
                .invoiceNumber(summary.invoiceNumber())
                .amountPaid(summary.amountPaid())
                .balanceDue(summary.balanceDue())
                .createdAt(summary.createdAt())
                .updatedAt(summary.updatedAt())
                .createdBy(summary.createdBy())
                .updatedBy(summary.updatedBy())
                .lines(lines)
                .build();
    }

    private SalesOrderLineResponse toLineResponse(SalesOrderLineSummary summary) {
        return SalesOrderLineResponse.builder()
                .orderLineId(summary.orderLineId())
                .itemSku(summary.itemSku())
                .itemDescription(summary.itemDescription())
                .quantity(summary.quantity())
                .unitPrice(summary.unitPrice())
                .discountPercent(summary.discountPercent())
                .discountAmount(summary.discountAmount())
                .lineSubtotal(summary.lineSubtotal())
                .taxAmount(summary.taxAmount())
                .lineTotal(summary.lineTotal())
                .customerNote(summary.customerNote())
                .internalNote(summary.internalNote())
                .fulfillmentStatus(summary.fulfillmentStatus())
                .priceSource(summary.priceSource())
                .reasonCode(summary.reasonCode())
                .sourceType(summary.sourceType())
                .sourceId(summary.sourceId())
                .sourceLineId(summary.sourceLineId())
                .serialNumbers(summary.serialNumbers())
                .returnable(summary.returnable())
                .build();
    }
}
