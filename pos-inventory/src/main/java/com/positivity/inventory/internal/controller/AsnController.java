package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.service.AsnService;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class AsnController {

    private final AsnService asnService;

    @PostMapping("/asns")
    @PreAuthorize("hasAuthority('inventory:asn:create')")
    @EmitEvent(id = "INVENTORY_ASN_CREATE", apiVersion = "1")
    public ResponseEntity<AsnResponse> createAsn(@Valid @RequestBody CreateAsnRequest request) {
        // ADR-0018 deviation: actor resolved in controller following existing
        // pos-inventory module convention.
        // The module pattern extracts actorId in controllers and passes to service
        // layer (see ReceivingController).
        // Full service-layer resolution is tracked as a module-wide refactor for a
        // future ADR update.
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        AsnResponse response = asnService.createAsn(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/asns/{asnId}")
    @PreAuthorize("hasAuthority('inventory:asn:view')")
    @EmitEvent(id = "INVENTORY_ASN_GET", apiVersion = "1")
    public ResponseEntity<AsnResponse> getAsn(@PathVariable UUID asnId) {
        return ResponseEntity.ok(asnService.getAsn(asnId));
    }

    @PostMapping("/goods-receipts")
    @PreAuthorize("hasAuthority('inventory:goods_receipt:create')")
    @EmitEvent(id = "INVENTORY_GOODS_RECEIPT_CREATE", apiVersion = "1")
    public ResponseEntity<GoodsReceiptResponse> createGoodsReceipt(
            @Valid @RequestBody CreateGoodsReceiptRequest request) {
        // ADR-0018 deviation: actor resolved in controller following existing
        // pos-inventory module convention.
        // The module pattern extracts actorId in controllers and passes to service
        // layer (see ReceivingController).
        // Full service-layer resolution is tracked as a module-wide refactor for a
        // future ADR update.
        String actorUserId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        GoodsReceiptResponse response = asnService.createGoodsReceipt(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/goods-receipts/{receiptId}")
    @PreAuthorize("hasAuthority('inventory:goods_receipt:view')")
    @EmitEvent(id = "INVENTORY_GOODS_RECEIPT_GET", apiVersion = "1")
    public ResponseEntity<GoodsReceiptResponse> getGoodsReceipt(@PathVariable UUID receiptId) {
        return ResponseEntity.ok(asnService.getGoodsReceipt(receiptId));
    }
}