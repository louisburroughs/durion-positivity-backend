package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.inventory.service.PurchaseOrderService;
import com.positivity.security.common.SecurityContextHelper;
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
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @PostMapping
    @PreAuthorize("hasAuthority('inventory:purchase_order:create')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_CREATE", apiVersion = "1")
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        PurchaseOrderResponse response = purchaseOrderService.createPurchaseOrder(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/{poId}")
    @PreAuthorize("hasAuthority('inventory:purchase_order:view')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_GET", apiVersion = "1")
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrder(@PathVariable UUID poId) {
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrder(poId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:purchase_order:view')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_LIST", apiVersion = "1")
    public ResponseEntity<Page<PurchaseOrderResponse>> listPurchaseOrders(
            @ModelAttribute ListPurchaseOrdersRequest filter,
            Pageable pageable) {
        return ResponseEntity.ok(purchaseOrderService.listPurchaseOrders(filter, pageable));
    }

    @PostMapping("/{poId}/approve")
    @PreAuthorize("hasAuthority('inventory:purchase_order:approve')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_APPROVE", apiVersion = "1")
    public ResponseEntity<PurchaseOrderResponse> approvePurchaseOrder(
            @PathVariable UUID poId,
            @Valid @RequestBody ApprovePurchaseOrderRequest request) {
        // ADR-0018 deviation: actor resolved in controller following existing
        // pos-inventory module convention.
        // The module pattern extracts actorId in controllers and passes to service
        // layer (see ReceivingController).
        // Full service-layer resolution is tracked as a module-wide refactor for a
        // future ADR update.
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        return ResponseEntity.ok(purchaseOrderService.approvePurchaseOrder(poId, request, actorUserId));
    }

    @PostMapping("/{poId}/revisions")
    @PreAuthorize("hasAuthority('inventory:purchase_order:create')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_REVISE", apiVersion = "1")
    public ResponseEntity<PurchaseOrderResponse> revisePurchaseOrder(
            @PathVariable UUID poId,
            @Valid @RequestBody RevisePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        return ResponseEntity.ok(purchaseOrderService.revisePurchaseOrder(poId, request, actorUserId));
    }

    @PostMapping("/{poId}/cancel")
    @PreAuthorize("hasAuthority('inventory:purchase_order:approve')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_CANCEL", apiVersion = "1")
    public ResponseEntity<PurchaseOrderResponse> cancelPurchaseOrder(@PathVariable UUID poId) {
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        return ResponseEntity.ok(purchaseOrderService.cancelPurchaseOrder(poId, actorUserId));
    }

    @PostMapping("/{poId}/receive")
    @PreAuthorize("hasAuthority('inventory:purchase_order:receive')")
    @EmitEvent(id = "INVENTORY_PURCHASE_ORDER_RECEIVE", apiVersion = "1")
    public ResponseEntity<ReceivePurchaseOrderResponse> receivePurchaseOrder(
            @PathVariable UUID poId,
            @Valid @RequestBody ReceivePurchaseOrderRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        return ResponseEntity.ok(purchaseOrderService.receivePurchaseOrder(poId, request, actorUserId));
    }
}
