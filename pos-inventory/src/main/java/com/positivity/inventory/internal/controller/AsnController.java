package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.observability.BusinessSpanSupport;
import com.positivity.inventory.service.AsnService;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AsnController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-inventory");
    private static final String DOMAIN = "inventory";
    private static final String TEAM = "inventory-eng";

    private final AsnService asnService;

    @PostMapping("/asns")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:asn:create"})
    @PreAuthorize("hasAuthority('inventory:asn:create')")
    @EmitEvent(id = "INVENTORY_ASN_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create ASN",
            description = "Creates an advanced shipping notice for inbound inventory",
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
    public ResponseEntity<AsnResponse> createAsn(@Valid @RequestBody CreateAsnRequest request) {
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
    @PreAuthorize("hasAuthority('inventory:asn:view')")
    @EmitEvent(id = "INVENTORY_ASN_GET", apiVersion = "1")
    @Operation(
            summary = "Get ASN",
            description = "Retrieves an ASN by identifier",
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
    @PreAuthorize("hasAuthority('inventory:goods_receipt:create')")
    @EmitEvent(id = "INVENTORY_GOODS_RECEIPT_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create goods receipt",
            description = "Creates a goods receipt for an inbound shipment",
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
    public ResponseEntity<GoodsReceiptResponse> createGoodsReceipt(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Goods receipt creation payload",
                            content = @Content(schema = @Schema(implementation = CreateGoodsReceiptRequest.class)))
                    @Valid
                    @RequestBody
                    CreateGoodsReceiptRequest request) {
        Span span = TRACER.spanBuilder("Receive Goods").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Receive Goods");
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
                    .orElseThrow(() -> new IllegalStateException("No current user"));
            GoodsReceiptResponse response = asnService.createGoodsReceipt(request, actorUserId);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Received goods for receipt {}", response.getReceiptId());
            return ResponseEntity.status(201).body(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @GetMapping("/goods-receipts/{receiptId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:goods_receipt:view"})
    @PreAuthorize("hasAuthority('inventory:goods_receipt:view')")
    @EmitEvent(id = "INVENTORY_GOODS_RECEIPT_GET", apiVersion = "1")
    @Operation(
            summary = "Get goods receipt",
            description = "Retrieves a goods receipt by identifier",
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
