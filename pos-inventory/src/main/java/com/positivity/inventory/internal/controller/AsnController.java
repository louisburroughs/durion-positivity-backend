package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.receiving.service.AsnService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
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
@Tag(name = "ASN", description = "Advanced Shipping Notice and goods receipt endpoints")
public class AsnController {

    private final AsnService asnService;

    @PostMapping("/asns")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:asn:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ASN_CREATE + "')")
    @EmitEvent(id = "INVENTORY_ASN_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createAsn",
            summary = "Create ASN",
            description = """
                    Creates an advance shipping notice (ASN) declaring an inbound vendor shipment against one or \
                    more approved purchase orders, stored in LOADED status with its declared lines.
                    Use this tool when a vendor announces a shipment before it arrives; do not use \
                    createGoodsReceipt, which records goods actually received and posts stock, and do not use \
                    createReceivingSession, which starts a line-by-line receiving workflow.
                    Preconditions: every purchase order in relatedPoIds must exist and be APPROVED, and no ASN may \
                    already exist for the same vendorId and asnReferenceNumber pair.
                    Required inputs: vendorId (UUID), asnReferenceNumber, relatedPoIds (non-empty) and lineItems \
                    each naming poId and sku plus either quantityShipped in base UoM or the \
                    documentUom/documentQuantity pair, from which the base quantity is derived; shipDate and \
                    expectedArrivalDate are optional.
                    Emits an INVENTORY_ASN_CREATE event; no stock is posted and no on-hand quantity changes until \
                    a goods receipt or receiving session references the ASN.
                    Returns 409 when an ASN with the same vendor and reference number already exists, 400 when a \
                    related purchase order is unknown or not APPROVED, and 422 when a documentUom has no \
                    conversion path to the product's base UoM.
                    """,
            tags = {"ASN"})
    @ApiResponse(
            responseCode = "201",
            description = "ASN created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AsnResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required ASN create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "ASN conflict (duplicate or state conflict)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Document UoM has no conversion path to the product's base UoM",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AsnResponse> createAsn(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Shipment declaration from the vendor: the ASN header plus the declared lines.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateAsnRequest.class),
                                            examples = @ExampleObject(name = "Single-line ASN", value = """
                                                                    {"vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "asnReferenceNumber":"ASN-2026-000123",
                                                                     "relatedPoIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02"],
                                                                     "shipDate":"2026-08-10",
                                                                     "expectedArrivalDate":"2026-08-15",
                                                                     "lineItems":[{"poId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                       "poLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                                                                       "sku":"SKU-10042","quantityShipped":12,
                                                                       "unitOfMeasure":"EA","unitCostMinor":1499,
                                                                       "lotNumber":"LOT-2026-0042"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateAsnRequest request) {
        // ADR-0018 deviation: actor resolved in controller following existing
        // pos-inventory module convention.
        // The module pattern extracts actorId in controllers and passes to service
        // layer (see ReceivingController).
        // Full service-layer resolution is tracked as a module-wide refactor for a
        // future ADR update.
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
        AsnResponse response = asnService.createAsn(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/asns/{asnId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:asn:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ASN_VIEW + "')")
    @EmitEvent(id = "INVENTORY_ASN_GET", apiVersion = "1")
    @Operation(
            operationId = "getAsn",
            summary = "Get ASN",
            description = """
                    Returns one advance shipping notice with its status, dates and per-line shipped and received \
                    quantities.
                    Use this tool when the asnId is already known; use getGoodsReceipt instead to inspect what was \
                    actually received on a specific receipt.
                    Preconditions: the ASN must exist.
                    Required inputs: asnId (UUIDv7) path parameter; there is no request body.
                    Emits an INVENTORY_ASN_GET audit event; no stock state changes, this is a read-only projection.
                    Returns 404 when no ASN exists for the supplied id.
                    """,
            tags = {"ASN"})
    @ApiResponse(
            responseCode = "200",
            description = "ASN returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AsnResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required ASN view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "ASN not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AsnResponse> getAsn(
            @Parameter(description = "ASN identifier", required = true) @PathVariable UUID asnId) {
        return ResponseEntity.ok(asnService.getAsn(asnId));
    }

    @PostMapping("/goods-receipts")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:goods_receipt:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.GOODS_RECEIPT_CREATE + "')")
    @EmitEvent(id = "INVENTORY_GOODS_RECEIPT_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createGoodsReceipt",
            summary = "Create Goods Receipt",
            description = """
                    Records a goods receipt against a purchase order, posting GOODS_RECEIPT ledger entries that \
                    increase on-hand stock at the receiving location.
                    Use this tool when goods physically arrive and should enter stock in one posting; do not use \
                    createAsn, which only declares an expected shipment, and do not use the session-based flow of \
                    createReceivingSession and receiveItemsIntoStaging.
                    Preconditions: the purchase order must exist and be APPROVED or PARTIALLY_RECEIVED, the ASN \
                    when supplied must exist, and the receipt total may not exceed the purchase order's open \
                    balance unless the caller holds inventory:goods_receipt:override.
                    Required inputs: poId (UUID), locationId (UUID) and lines each naming sku and unitCostMinor \
                    plus either a whole-number quantityReceived in base UoM or the documentUom/documentQuantity \
                    pair; lotNumber is mandatory for LOT-tracked SKUs, serialNumbers must enumerate exactly the \
                    received quantity for SERIAL-tracked SKUs, and asnId is optional.
                    Emits an INVENTORY_GOODS_RECEIPT_CREATE event, decrements the purchase order's open balance, \
                    moves the PO to PARTIALLY_RECEIVED or FULLY_RECEIVED, and updates the linked ASN's received \
                    quantities and status.
                    Returns 404 when the ASN or a referenced PO line cannot be resolved, 400 when the purchase \
                    order is unknown or not receivable, 403 when the caller lacks goods-receipt create authority, \
                    and 422 when the receipt would exceed the open balance without the override authority, a UoM \
                    has no conversion path, a LOT-tracked line omits lotNumber, or a serialized line's serial count \
                    mismatches the received quantity.
                    """,
            tags = {"ASN"})
    @ApiResponse(
            responseCode = "201",
            description = "Goods receipt created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = GoodsReceiptResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required goods receipt create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Referenced source document not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Goods receipt conflict",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Over-receipt without override authority, UoM conversion undefined, lot number required,"
                    + " or serial count mismatch",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<GoodsReceiptResponse> createGoodsReceipt(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Received goods to post into stock: the target PO, receiving location and the"
                                    + " costed receipt lines.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateGoodsReceiptRequest.class),
                                            examples = @ExampleObject(name = "Lot-tracked receipt line", value = """
                                                                    {"poId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "asnId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05",
                                                                     "lines":[{"poLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                                                                       "sku":"SKU-10042","quantityReceived":8,
                                                                       "unitCostMinor":1499,
                                                                       "lotNumber":"LOT-2026-0042"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateGoodsReceiptRequest request) {
        // ADR-0018 deviation: actor resolved in controller following existing
        // pos-inventory module convention.
        // The module pattern extracts actorId in controllers and passes to service
        // layer (see ReceivingController).
        // Full service-layer resolution is tracked as a module-wide refactor for a
        // future ADR update.
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
        GoodsReceiptResponse response = asnService.createGoodsReceipt(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/goods-receipts/{receiptId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:goods_receipt:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.GOODS_RECEIPT_VIEW + "')")
    @EmitEvent(id = "INVENTORY_GOODS_RECEIPT_GET", apiVersion = "1")
    @Operation(
            operationId = "getGoodsReceipt",
            summary = "Get Goods Receipt",
            description = """
                    Returns one goods receipt with its accrued amounts and per-line received quantities and costs.
                    Use this tool when the receiptId is already known; use getAsn instead to check the shipment \
                    declaration and its outstanding quantities.
                    Preconditions: the goods receipt must exist.
                    Required inputs: receiptId (UUIDv7) path parameter; there is no request body.
                    Emits an INVENTORY_GOODS_RECEIPT_GET audit event; no stock state changes, this is a read-only \
                    projection.
                    Returns 404 when no goods receipt exists for the supplied id.
                    """,
            tags = {"ASN"})
    @ApiResponse(
            responseCode = "200",
            description = "Goods receipt returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = GoodsReceiptResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required goods receipt view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Goods receipt not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<GoodsReceiptResponse> getGoodsReceipt(
            @Parameter(description = "Goods receipt identifier", required = true) @PathVariable UUID receiptId) {
        return ResponseEntity.ok(asnService.getGoodsReceipt(receiptId));
    }
}
