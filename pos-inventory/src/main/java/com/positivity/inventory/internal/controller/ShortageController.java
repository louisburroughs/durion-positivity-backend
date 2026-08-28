package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.internal.reservation.service.ShortageResolutionService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/shortage")
@RequiredArgsConstructor
@Validated
@Tag(name = "Shortage Resolution", description = "Shortage resolution recommendation endpoints")
public class ShortageController {

    private final ShortageResolutionService shortageResolutionService;

    @GetMapping("/options")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.SHORTAGE_VIEW + "')")
    @Operation(
            operationId = "listShortageOptions",
            summary = "List computed shortage options",
            description = """
                    Computes the live resolution options for an inventory shortage: BACKORDER (always offered; \
                    expected date from the SKU's replenishment lead time at the site, defaulting to 7 days), \
                    SUBSTITUTE (substitution-group members with positive ATP at the site, with cost delta), \
                    TRANSFER_IN (sibling sites whose surplus of on-hand minus allocated covers the shortage, 3-day \
                    lead), EMERGENCY_PURCHASE (always offered; vendor-selected lead and cost delta, defaulting to \
                    14 days) and CANCEL_LINE (always offered, immediate).
                    Use this tool to present choices before calling resolveShortage with the selected optionType; \
                    do not call resolveShortage blind, because SUBSTITUTE and TRANSFER_IN need fields \
                    (substituteSku, sourceLocationId) that this computation supplies.
                    Preconditions: none beyond a positive shortQuantity; SUBSTITUTE and TRANSFER_IN options appear \
                    only when locationId is provided, and SUBSTITUTE additionally requires sku to parse as a \
                    product UUID.
                    Required inputs: allocationId (UUID), sku (non-blank) and shortQuantity (positive, decimal-capable \
                    per the product's catalog precision_scale declaration); \
                    workorderLineId and locationId are optional but drive which options appear.
                    No events are emitted and no state changes; this is a read-only computation.
                    Returns 400 when shortQuantity is not positive or sku is blank.
                    """,
            tags = {"Shortage Resolution"})
    @ApiResponse(
            responseCode = "200",
            description = "Shortage options returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ShortageOptionDto.class))))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required shortage view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ShortageOptionDto>> listShortageOptions(
            @Parameter(description = "Allocation experiencing the shortage") @RequestParam UUID allocationId,
            @Parameter(description = "SKU / stock-item identifier that is short") @RequestParam @NotBlank String sku,
            @Parameter(description = "Quantity that is short") @RequestParam @Positive BigDecimal shortQuantity,
            @Parameter(description = "Workorder line whose demand is short") @RequestParam(required = false)
                    UUID workorderLineId,
            @Parameter(description = "Site the demand is short at") @RequestParam(required = false) UUID locationId) {
        return ResponseEntity.ok(shortageResolutionService.computeShortageOptions(
                allocationId, workorderLineId, sku, shortQuantity, locationId));
    }

    @PostMapping("/resolve")
    @EmitEvent(id = "INVENTORY_SHORTAGE_RESOLVE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:resolve"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.SHORTAGE_RESOLVE + "')")
    @Operation(
            operationId = "resolveShortage",
            summary = "Resolve shortage",
            description = """
                    Executes one chosen shortage-resolution option atomically and records the created artifact: \
                    BACKORDER opens a backorder, SUBSTITUTE reserves the substitute SKU through the reservation \
                    upsert, TRANSFER_IN creates a cross-site transfer order, EMERGENCY_PURCHASE stores a purchase \
                    suggestion, and CANCEL_LINE cancels the workorder line's reservation.
                    Use this tool after picking an option from listShortageOptions; do not use \
                    createOrUpdateReservation directly for substitutes or cancelReservation directly for line \
                    cancels when an auditable, idempotent resolution record is wanted.
                    Preconditions: option-specific — workorderLineId for BACKORDER and SUBSTITUTE, substituteSku \
                    (a product UUID with positive ATP at locationId when locationId is given) for SUBSTITUTE, and \
                    both sourceLocationId and locationId for TRANSFER_IN.
                    Required inputs: idempotencyKey (unique per resolution attempt — a replay with the same key \
                    returns the stored result without re-executing), allocationId (UUID), sku, a positive \
                    shortQuantity and optionType (BACKORDER, SUBSTITUTE, TRANSFER_IN, EMERGENCY_PURCHASE or \
                    CANCEL_LINE), plus the option-specific fields above; notes is optional free text.
                    Emits an INVENTORY_SHORTAGE_RESOLVE event and persists a shortage-resolution record \
                    referencing the artifact (BACKORDER, RESERVATION, TRANSFER_ORDER, PURCHASE_SUGGESTION or NONE).
                    Returns 422 with per-case codes when execution preconditions fail — MISSING_FIELD for an \
                    absent option-specific field, SUBSTITUTE_UNAVAILABLE when the substitute has no ATP at the \
                    site, INVALID_IDENTIFIER when substituteSku is not a UUID — and 400 when idempotencyKey, \
                    allocationId, sku or optionType is missing.
                    """,
            tags = {"Shortage Resolution"})
    @ApiResponse(
            responseCode = "200",
            description = "Shortage resolved",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ShortageResolutionResultDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required shortage resolution authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Unable to resolve shortage under current constraints",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ShortageResolutionResultDto> resolveShortage(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The chosen resolution option with its idempotency key and the"
                                    + " option-specific fields listShortageOptions supplied.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Backorder the short demand", value = """
                                                                    {"idempotencyKey":"shortage-resolve-018f0a1b-0001",
                                                                     "allocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "optionType":"BACKORDER",
                                                                     "sku":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "shortQuantity":3,
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a0c",
                                                                     "workorderLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ShortageResolveRequest request) {
        return ResponseEntity.ok(shortageResolutionService.resolveShortage(request));
    }
}
