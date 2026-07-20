package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.inventory.internal.observability.BusinessSpanSupport;
import com.positivity.inventory.service.PurchaseOrderService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/purchase-orders")
@RequiredArgsConstructor
@Tag(name = "Purchase Orders", description = "Purchase order creation, lifecycle, and receiving endpoints")
public class PurchaseOrderController {

    private static final String NO_CURRENT_USER = "No current user";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PurchaseOrderController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-inventory");
    private static final String DOMAIN = "inventory";
    private static final String TEAM = "inventory-eng";

    private final PurchaseOrderService purchaseOrderService;

    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:purchase_order:create"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:create')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create purchase order",
            description = "Creates a purchase order with requested line items",
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
            @Valid @RequestBody CreatePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        PurchaseOrderResponse response = purchaseOrderService.createPurchaseOrder(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/{poId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:purchase_order:view"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:view')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_GET", apiVersion = "1")
    @Operation(
            summary = "Get purchase order",
            description = "Retrieves a purchase order by identifier",
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
            scopes = {"inventory:purchase_order:view"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:view')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_LIST", apiVersion = "1")
    @Operation(
            summary = "List purchase orders",
            description = "Lists purchase orders using filter and pagination parameters",
            tags = {"Purchase Orders"})
    @ApiResponse(responseCode = "200", description = "Purchase orders returned")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<PurchaseOrderResponse>> listPurchaseOrders(
            @ModelAttribute ListPurchaseOrdersRequest filter, Pageable pageable) {
        return ResponseEntity.ok(purchaseOrderService.listPurchaseOrders(filter, pageable));
    }

    @PostMapping("/{poId}/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:purchase_order:approve"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:approve')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_APPROVE", apiVersion = "1")
    @Operation(
            summary = "Approve purchase order",
            description = "Approves a purchase order and transitions it to an approvable state",
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
            @Valid @RequestBody ApprovePurchaseOrderRequest request) {
        Span span = TRACER.spanBuilder("Approve Purchase Order").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Approve Purchase Order");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            // ADR-0018 deviation: actor resolved in controller following existing
            // pos-inventory module convention.
            // The module pattern extracts actorId in controllers and passes to service
            // layer (see ReceivingController).
            // Full service-layer resolution is tracked as a module-wide refactor for a
            // future ADR update.
            String actorUserId = SecurityContextHelper.getCurrentUsername()
                    .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
            PurchaseOrderResponse response = purchaseOrderService.approvePurchaseOrder(poId, request, actorUserId);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Approved purchase order {}", poId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping("/{poId}/revisions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:purchase_order:create"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:create')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_REVISE", apiVersion = "1")
    @Operation(
            summary = "Revise purchase order",
            description = "Creates a revision for an existing purchase order",
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
            @Valid @RequestBody RevisePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(purchaseOrderService.revisePurchaseOrder(poId, request, actorUserId));
    }

    @PostMapping("/{poId}/cancel")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:purchase_order:approve"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:approve')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_CANCEL", apiVersion = "1")
    @Operation(
            summary = "Cancel purchase order",
            description = "Cancels a purchase order that is no longer needed",
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

    @PostMapping("/{poId}/receive")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:purchase_order:receive"})
    @PreAuthorize("hasAuthority('inventory:purchase_order:receive')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_RECEIVE", apiVersion = "1")
    @Operation(
            summary = "Receive purchase order",
            description = "Receives approved purchase order quantities and posts resulting ledger movements",
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase order received",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReceivePurchaseOrderResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order receive authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Purchase order is in a conflicting state",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReceivePurchaseOrderResponse> receivePurchaseOrder(
            @Parameter(description = "Purchase order identifier", required = true) @PathVariable UUID poId,
            @Valid @RequestBody ReceivePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(purchaseOrderService.receivePurchaseOrder(poId, request, actorUserId));
    }
}
