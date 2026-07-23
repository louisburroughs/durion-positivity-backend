package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.service.TransferOrderService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
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
    @PreAuthorize("hasAuthority('inventory:transfer:create')")
    @Operation(
            summary = "Create transfer order",
            description = "Creates a cross-site transfer order in DRAFT. Source and destination must be different"
                    + " sites, both ACTIVE in the location roster (DECISION-INVENTORY-009), with at least one"
                    + " positive-quantity line.",
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
            @Valid @RequestBody CreateTransferOrderRequest request) {
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
    @PreAuthorize("hasAuthority('inventory:transfer:view')")
    @Operation(
            summary = "Get transfer order",
            description = "Retrieves one transfer order with its lines",
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
    @PreAuthorize("hasAuthority('inventory:transfer:view')")
    @Operation(
            summary = "List transfer orders",
            description = "Lists transfer orders filtered by status, source site, and destination site",
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
    @PreAuthorize("hasAuthority('inventory:transfer:dispatch')")
    @Operation(
            summary = "Approve transfer order",
            description = "Approves a DRAFT transfer order (DRAFT to APPROVED). Only available when"
                    + " pos.inventory.transfer.approval-required=true; with the flag off (default), DRAFT orders"
                    + " dispatch directly and this endpoint returns 409 (decision D-8).",
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
    @PreAuthorize("hasAuthority('inventory:transfer:create')")
    @Operation(
            summary = "Cancel transfer order",
            description = "Cancels a transfer order before dispatch (DRAFT/APPROVED to CANCELLED). After dispatch"
                    + " the stock has physically moved: cancellation returns 409 and discrepancies resolve via"
                    + " short-close (parity-C3).",
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
