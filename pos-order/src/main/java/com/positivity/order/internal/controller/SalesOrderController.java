package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.AddItemRequest;
import com.positivity.order.internal.dto.CreateCartRequest;
import com.positivity.order.internal.dto.LinkSourceRequest;
import com.positivity.order.internal.dto.OrderDiscountRequest;
import com.positivity.order.internal.dto.SalesOrderLineResponse;
import com.positivity.order.internal.dto.SalesOrderResponse;
import com.positivity.order.internal.dto.UpdateItemRequest;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.service.SalesOrderService;
import com.positivity.order.service.model.AddItemCommand;
import com.positivity.order.service.model.CreateCartCommand;
import com.positivity.order.service.model.CreateCartResult;
import com.positivity.order.service.model.OrderDiscountCommand;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
            summary = "Create a sales order cart",
            description = "Create a new sales order cart for a customer, terminal, and optional vehicle context. "
                    + "Supports the Idempotency-Key header: a replayed key returns the original cart with 200 "
                    + "instead of creating a duplicate; a replayed key with a different payload returns 409.",
            tags = {"Sales Orders"})
    @PostMapping("/carts")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CREATE + "')")
    @EmitEvent(id = "ORDER_CART_CREATE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> createCart(
            @Parameter(description = "Client idempotency key; replays return the original cart")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreateCartRequest request) {
        CreateCartResult result = salesOrderService.createCart(new CreateCartCommand(
                request.getClerkId(),
                request.getTerminalId(),
                request.getCustomerId(),
                request.getVehicleId(),
                request.getLocationId(),
                request.getLabel(),
                request.getGeneralNote(),
                idempotencyKey));
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(toResponse(result.summary()));
    }

    @Operation(
            summary = "List sales order carts",
            description = "List carts filtered by clerk, terminal, and/or status — the draft-parking/resume "
                    + "surface. Line items are omitted from list results.",
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
            summary = "Add an item to a sales order cart",
            description =
                    "Add a line item to an existing sales order cart using SKU, quantity, and optional pricing context."
                            + " A client-supplied lineUuid makes the call replay-safe: a replay with a known lineUuid"
                            + " updates the existing line instead of duplicating it.",
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/items")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_LINE_CREATE + "')")
    @EmitEvent(id = "ORDER_CART_ITEM_ADD", apiVersion = "1")
    public ResponseEntity<SalesOrderLineResponse> addItem(
            @PathVariable UUID orderId, @Valid @RequestBody AddItemRequest request) {
        SalesOrderLineSummary line = salesOrderService.addItem(
                orderId,
                new AddItemCommand(
                        request.getItemSku(),
                        request.getQuantity(),
                        request.getReasonCode(),
                        request.getManualPrice(),
                        request.getLineUuid(),
                        request.getCustomerNote(),
                        request.getInternalNote()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toLineResponse(line));
    }

    @Operation(
            summary = "Update a sales order cart item quantity",
            description = "Update the quantity for an existing sales order line item in a cart.",
            tags = {"Sales Orders"})
    @PutMapping("/carts/{orderId}/items/{lineId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_LINE_EDIT + "')")
    @EmitEvent(id = "ORDER_CART_ITEM_UPDATE", apiVersion = "1")
    public ResponseEntity<SalesOrderLineResponse> updateItemQuantity(
            @PathVariable UUID orderId, @PathVariable UUID lineId, @Valid @RequestBody UpdateItemRequest request) {
        SalesOrderLineSummary line = salesOrderService.updateItemQuantity(orderId, lineId, request.getQuantity());
        return ResponseEntity.ok(toLineResponse(line));
    }

    @Operation(
            summary = "Remove an item from a sales order cart",
            description = "Remove a line item from an existing sales order cart.",
            tags = {"Sales Orders"})
    @DeleteMapping("/carts/{orderId}/items/{lineId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_LINE_DELETE + "')")
    @EmitEvent(id = "ORDER_CART_ITEM_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> removeItem(@PathVariable UUID orderId, @PathVariable UUID lineId) {
        salesOrderService.removeItem(orderId, lineId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get a sales order by ID",
            description = "Retrieve a sales order cart and its current line items by identifier.",
            tags = {"Sales Orders"})
    @GetMapping("/carts/{orderId}")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_VIEW + "')")
    public ResponseEntity<SalesOrderResponse> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.getOrder(orderId)));
    }

    @Operation(
            summary = "Apply an order-level discount",
            description = "Apply or replace the order-level discount (PERCENT or AMOUNT), allocated pro-rata"
                    + " across lines. DRAFT orders only.",
            tags = {"Sales Orders"})
    @PutMapping("/carts/{orderId}/discount")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_DISCOUNT + "')")
    @EmitEvent(id = "ORDER_CART_DISCOUNT_APPLY", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> applyOrderDiscount(
            @PathVariable UUID orderId, @Valid @RequestBody OrderDiscountRequest request) {
        SalesOrderSummary updated = salesOrderService.applyOrderDiscount(
                orderId, new OrderDiscountCommand(request.getType(), request.getValue(), request.getReasonCode()));
        return ResponseEntity.ok(toResponse(updated));
    }

    @Operation(
            summary = "Remove the order-level discount",
            description = "Clear any order-level discount and recompute totals. DRAFT orders only.",
            tags = {"Sales Orders"})
    @DeleteMapping("/carts/{orderId}/discount")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_DISCOUNT + "')")
    @EmitEvent(id = "ORDER_CART_DISCOUNT_REMOVE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> clearOrderDiscount(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.clearOrderDiscount(orderId)));
    }

    @Operation(
            summary = "Convert a cart to a counter quote",
            description = "Final reprice + tax computation, then DRAFT → QUOTED with a validity horizon."
                    + " Counter quotes only: workorder-linked orders are quoted via pos-workorder estimates"
                    + " and are rejected here (spec Q3).",
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/quote")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_QUOTE + "')")
    @EmitEvent(id = "ORDER_CART_QUOTE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> quote(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.quote(orderId)));
    }

    @Operation(
            summary = "Reopen a counter quote",
            description = "QUOTED → DRAFT: clears the validity horizon, best-effort reprices, and marks tax stale.",
            tags = {"Sales Orders"})
    @PostMapping("/carts/{orderId}/quote/reopen")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_QUOTE + "')")
    @EmitEvent(id = "ORDER_CART_QUOTE_REOPEN", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> reopenQuote(@PathVariable UUID orderId) {
        return ResponseEntity.ok(toResponse(salesOrderService.reopenQuote(orderId)));
    }

    @Operation(
            summary = "Link a source to a sales order",
            description = "Associate an external source reference with an existing sales order cart.",
            tags = {"Sales Orders"})
    @PatchMapping("/carts/{orderId}/source")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_EDIT + "')")
    @EmitEvent(id = "ORDER_LINK_SOURCE", apiVersion = "1")
    public ResponseEntity<SalesOrderResponse> linkSource(
            @PathVariable UUID orderId, @Valid @RequestBody LinkSourceRequest request) {
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
                .build();
    }
}
