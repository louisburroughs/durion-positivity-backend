package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.order.service.SupplierOrderService;
import com.positivity.supplier.internal.order.service.model.OrderTransmissionStatus;
import com.positivity.supplier.internal.order.service.model.TransmissionResolutionRequest;
import com.positivity.supplier.internal.security.SupplierPermissions;
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
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purchase-order transmission ledger and its manual-resolution actions (ADR-0052, CAP-320
 * #1226).
 *
 * <h2>What this API is not</h2>
 *
 * It does not place orders and it does not re-send them. Ordering is a command the ordering
 * domain publishes on {@code supplier.commands.v1}; re-sending is the single operation ADR-0052
 * exists to prevent, so no endpoint offers it — not even for a transmission an operator is
 * certain never arrived. That case is {@code mark-not-received}, which ends the transmission and
 * leaves re-ordering to the domain that owns the purchase order, under a new intent and a new
 * document id.
 *
 * <h2>Two permissions, not one</h2>
 *
 * Reading the ledger is {@code supplier:transmission:read}. Resolving a stuck transmission is
 * {@code supplier:transmission:resolve}, and it is gated apart because it is not a read with a
 * side effect — it is an operator asserting, on the system's behalf, whether a vendor holds a
 * real order.
 */
@Tag(
        name = "Supplier Order Transmission",
        description = "Read the purchase-order transmission ledger and resolve transmissions awaiting manual review."
                + " No endpoint here places or re-sends an order.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/transmissions")
public class SupplierTransmissionController {

    private static final String UNAUTHENTICATED_DESCRIPTION = "Authentication is missing or the bearer token is"
            + " invalid. The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
            + " status, so clients must not attempt to parse an error envelope here.";

    private static final String CONFIRM_EXAMPLE = """
            {
              "action": "CONFIRM_WITH_VENDOR_REFERENCE",
              "evidence": "Called Michelin order desk 2026-08-14 09:40; they hold the order under MICH-770412 and \
            confirmed the delivery is scheduled.",
              "supplierOrderNumber": "MICH-770412"
            }""";

    private static final String NOT_RECEIVED_EXAMPLE = """
            {
              "action": "MARK_NOT_RECEIVED",
              "evidence": "Michelin order desk searched by our reference and by buyer account for 2026-08-14 and \
            found nothing; re-ordering will be raised as a new purchase-order revision."
            }""";

    private final SupplierOrderService orderService;

    @Operation(
            operationId = "listSupplierTransmissionsForPurchaseOrder",
            summary = "List the vendor transmissions of one purchase order",
            description = """
                    Returns every transmission intent recorded for a purchase order — initial, revision, replacement
                    and split — newest first, with what the vendor answered and, where a transmission is stuck, what a
                    human needs to resolve it.
                    Use this tool to answer "did this order actually reach the vendor, and what did they say"; do not
                    use it to read delivery schedules or despatches, which reach the ordering domain as
                    supplier.orderstatus.changed events and belong on the purchase-order timeline.
                    Preconditions: the caller must hold supplier:transmission:read; the purchase order need not exist
                    in this module, and an order that was never transmitted is a normal empty answer.
                    Required inputs: purchaseOrderId (UUIDv7) as a path parameter; there is no request body.
                    Emits a SUPPLIER_TRANSMISSION_LIST event. Read-only: nothing is transmitted, retried or changed.
                    Returns 200 with an empty array when the purchase order was never transmitted — that is a normal
                    answer, not a 404.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Transmission states for the purchase order, newest intent first; empty when never sent.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = OrderTransmissionStatus.class))))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks supplier:transmission:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/purchase-order/{purchaseOrderId}")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.TRANSMISSION_READ + "')")
    @EmitEvent(id = "SUPPLIER_TRANSMISSION_LIST", apiVersion = "1")
    public ResponseEntity<List<OrderTransmissionStatus>> listForPurchaseOrder(
            @Parameter(description = "Purchase-order aggregate id (UUIDv7).", required = true) @PathVariable
                    UUID purchaseOrderId) {
        return ResponseEntity.ok(orderService.findTransmissionsByPurchaseOrderId(purchaseOrderId));
    }

    @Operation(operationId = "getSupplierTransmission", summary = "Get one vendor transmission", description = """
                    Returns the current state of one transmission intent, including the wire document id an operator
                    would quote to a vendor and, when the transmission is stuck, why.
                    Use this tool when working a single transmission; do not use it to survey a purchase order's
                    transmission history, which is listSupplierTransmissionsForPurchaseOrder instead.
                    Preconditions: the caller must hold supplier:transmission:read and the transmission intent must
                    exist; a transmission is created only by pos-supplier consuming a supplier.order.requested
                    command, never by this API.
                    Required inputs: transmissionIntentId (UUIDv7) as a path parameter; there is no request body.
                    Emits a SUPPLIER_TRANSMISSION_GET event. Read-only.
                    Returns 200 with the transmission, or 404 when no transmission exists with that id.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The transmission's current state.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = OrderTransmissionStatus.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No transmission exists with the supplied id.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks supplier:transmission:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{transmissionIntentId}")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.TRANSMISSION_READ + "')")
    @EmitEvent(id = "SUPPLIER_TRANSMISSION_GET", apiVersion = "1")
    public ResponseEntity<OrderTransmissionStatus> getTransmission(
            @Parameter(description = "Transmission intent id (UUIDv7).", required = true) @PathVariable
                    UUID transmissionIntentId) {
        return ResponseEntity.ok(orderService.getTransmission(transmissionIntentId));
    }

    @Operation(
            operationId = "resolveSupplierTransmission",
            summary = "Resolve a transmission awaiting manual review",
            description = """
                    Applies one of the three ADR-0052 §4 resolutions to a transmission whose outcome could not be
                    established automatically: confirm-with-vendor-reference (the vendor accepted it),
                    mark-not-received (the vendor never got it — the intent ends and is never re-sent), or cancel.
                    Use this tool only after establishing the facts with the vendor; do not use it to retry a
                    transmission, which no endpoint offers because a blind re-send is how a purchase order becomes two
                    deliveries. Re-ordering after mark-not-received is a fresh request from the ordering domain, which
                    mints a new transmission intent and a new vendor document id.
                    Preconditions: the caller must hold supplier:transmission:resolve, the transmission must exist and
                    be in state MANUAL_REVIEW, and the operator must have established the facts with the vendor —
                    every other state either has a definite outcome already or is still being worked on
                    automatically.
                    Required inputs: transmissionIntentId (UUIDv7) as a path parameter, and a body carrying the action
                    plus free-text evidence; supplierOrderNumber is additionally required when confirming.
                    Emits a SUPPLIER_TRANSMISSION_RESOLVE event, records the actor and their evidence on the
                    transmission, and publishes supplier.order.confirmed or supplier.order.rejected for the
                    purchase-order timeline. The change is permanent — resolved transmissions are terminal.
                    Returns 200 with the resolved transmission, 400 when the evidence or vendor reference is missing,
                    404 when no such transmission exists, and 409 when the transmission is not awaiting manual review.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The transmission after resolution.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = OrderTransmissionStatus.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The request is missing evidence, or a vendor reference when confirming.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No transmission exists with the supplied id.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The transmission is not awaiting manual review, so it cannot be resolved by hand.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks supplier:transmission:resolve.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{transmissionIntentId}/resolve")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.TRANSMISSION_RESOLVE + "')")
    @EmitEvent(id = "SUPPLIER_TRANSMISSION_RESOLVE", apiVersion = "1")
    public ResponseEntity<OrderTransmissionStatus> resolveTransmission(
            @Parameter(description = "Transmission intent id (UUIDv7).", required = true) @PathVariable
                    UUID transmissionIntentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The resolution to apply and the operator's evidence for it. The evidence is"
                                    + " stored and published on the resulting event: weeks later it is the only record"
                                    + " of why the system believes a vendor did or did not receive this order.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = {
                                                @ExampleObject(
                                                        name = "Vendor confirmed by phone",
                                                        value = CONFIRM_EXAMPLE),
                                                @ExampleObject(
                                                        name = "Vendor never received it",
                                                        value = NOT_RECEIVED_EXAMPLE)
                                            }))
                    @Valid
                    @NotNull
                    @RequestBody
                    TransmissionResolutionRequest request) {
        return ResponseEntity.ok(orderService.resolveTransmission(transmissionIntentId, request));
    }
}
