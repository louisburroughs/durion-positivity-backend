package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.InventoryAvailabilityResponse;
import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.enums.InventorySourceType;
import com.positivity.inventory.internal.exception.InvalidParamCombinationException;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/inventory/availability")
@Tag(name = "Inventory Availability", description = "Inventory availability read/write endpoints")
public class InventoryAvailabilityController {

    private final InventoryAvailabilityService availabilityService;
    private final InventoryLeadTimeService inventoryLeadTimeService;

    public InventoryAvailabilityController(
            InventoryAvailabilityService availabilityService, InventoryLeadTimeService inventoryLeadTimeService) {
        this.availabilityService = availabilityService;
        this.inventoryLeadTimeService = inventoryLeadTimeService;
    }

    @GetMapping("/{productId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @Operation(
            operationId = "getAvailabilityByProduct",
            summary = "Query per-location inventory availability",
            description = """
                    Returns per-location availability for a product: on-hand, available-to-promise and the forecast \
                    quantities incomingQty, outgoingQty and projectedAvailable computed from open purchase orders, \
                    ASNs, reservations and released pick tasks; ATP in this per-location list subtracts hard \
                    allocations, soft reservations and expired ACTIVE lot on-hand from on-hand.
                    Use this tool when the productId is known and a per-location breakdown is wanted; use \
                    getAvailabilityBySku instead for a single aggregated view keyed by SKU, and \
                    getInventoryLeadTime for lead-time estimates.
                    Preconditions: none for the current view; an asOf request additionally requires the \
                    inventory:ledger:view authority because it exposes ledger history.
                    Required inputs: productId (UUID) path parameter; horizon (ISO-8601 instant) optionally bounds \
                    incomingQty/outgoingQty and excludes documents without an expected date; asOf (ISO-8601 \
                    instant) switches to historical on-hand computed by direct ledger aggregation, in which case \
                    availableToPromiseQuantity and the forecast fields are null because historical allocation state \
                    is not reliably reconstructable.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when asOf is combined with horizon (INVALID_PARAM_COMBINATION) or productId is \
                    missing, 403 when an asOf request lacks inventory:ledger:view, and 422 when asOf is in the \
                    future (AS_OF_IN_FUTURE).
                    """,
            tags = {"Inventory Availability"})
    @ApiResponse(
            responseCode = "200",
            description = "Availability returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = LocationAvailabilityDto.class))))
    @ApiResponse(responseCode = "400", description = "Invalid product identifier")
    // Issue #48: Expose on-hand and ATP grouped by location.
    public ResponseEntity<List<LocationAvailabilityDto>> queryInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId,
            @Parameter(
                            description =
                                    "Optional forecast horizon (ISO-8601 instant). Bounds incomingQty to supply expected "
                                            + "on or before this instant and outgoingQty to reservations due by it; documents "
                                            + "without an expected date are excluded from horizon-bounded results.")
                    @RequestParam(required = false)
                    java.time.Instant horizon,
            @Parameter(
                            description =
                                    "Optional historical instant (ISO-8601). Returns on-hand as of this instant by "
                                            + "direct ledger aggregation; ATP and forecast fields are null. Requires "
                                            + "'inventory:ledger:view'; future instants are rejected (422). "
                                            + "Cannot be combined with 'horizon'.")
                    @RequestParam(required = false)
                    java.time.Instant asOf) {
        log.info("GET /v1/inventory/availability/{} horizon={} asOf={}", productId, horizon, asOf);
        if (asOf != null && horizon != null) {
            throw new InvalidParamCombinationException("asOf cannot be combined with horizon");
        }
        if (asOf != null) {
            return ResponseEntity.ok(availabilityService.getAvailabilityByProductAsOf(productId, asOf));
        }
        return ResponseEntity.ok(availabilityService.getAvailabilityByProduct(productId, horizon));
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view", "inventory:on_hand:search"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "','"
            + InventoryPermissionRegistry.INVENTORY_SEARCH + "')")
    @Operation(
            operationId = "listAvailabilityBySku",
            summary = "Query inventory availability by SKU (list form)",
            description = """
                    Returns the same single aggregated availability view as getAvailabilityBySku, wrapped in a \
                    one-element array, and takes the query parameter name sku instead of productSku.
                    Use this tool only when a caller requires a list-shaped response from the root availability \
                    path; use getAvailabilityBySku instead for the plain object form, and getAvailabilityByProduct \
                    for a per-location breakdown.
                    Preconditions: the SKU must have at least one stock-summary row, meaning it has been received \
                    or counted at least once.
                    Required inputs: sku (string); locationId and storageLocationId (UUIDs) optionally narrow the \
                    scope, sourceType (WAREHOUSE, SUPPLIER or TRANSIT) selects the lookup strategy, and horizon \
                    (ISO-8601 instant) bounds the forecast quantities; locationId is only valid when sourceType is \
                    WAREHOUSE or omitted.
                    No events are emitted and no state changes; ATP here is on-hand minus hard allocations minus \
                    expired ACTIVE lot on-hand — soft reservations are not subtracted (ADR-0001).
                    Returns 404 when the SKU has no stock-summary rows, and 400 when locationId is combined with a \
                    non-WAREHOUSE sourceType (INVALID_PARAM_COMBINATION).
                    """,
            tags = {"Inventory Availability"})
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Availability list returned",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        array =
                                                @ArraySchema(
                                                        schema = @Schema(implementation = AvailabilityView.class)))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid request parameters",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "User lacks required read permission",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Product SKU or location not found",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "500",
                        description = "Unexpected server error",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class)))
            })
    // Issue: #665
    public ResponseEntity<List<AvailabilityView>> queryAvailabilityBySkuList(
            @Parameter(description = "Product SKU", required = true) @RequestParam String sku,
            @Parameter(description = "Location identifier") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Storage location identifier (optional; narrows to sub-location)")
                    @RequestParam(required = false)
                    UUID storageLocationId,
            @Parameter(description = "Inventory lookup strategy") @RequestParam(required = false)
                    InventorySourceType sourceType,
            @Parameter(
                            description =
                                    "Optional forecast horizon (ISO-8601 instant) bounding incomingQty/outgoingQty; "
                                            + "documents without an expected date are excluded from horizon-bounded results.")
                    @RequestParam(required = false)
                    java.time.Instant horizon) {
        log.info("GET /v1/inventory/availability sku={} locationId={} sourceType={}", sku, locationId, sourceType);
        return ResponseEntity.ok(List.of(resolveAvailability(sku, locationId, storageLocationId, sourceType, horizon)));
    }

    @GetMapping("/by-sku")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view", "inventory:on_hand:search"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "','"
            + InventoryPermissionRegistry.INVENTORY_SEARCH + "')")
    @Operation(
            operationId = "getAvailabilityBySku",
            summary = "Query inventory availability by SKU and location",
            description = """
                    Returns a single aggregated availability view for a SKU: on-hand, allocated, \
                    available-to-promise, the derived unit of measure and the forecast quantities incomingQty, \
                    outgoingQty and projectedAvailable.
                    Use this tool to check whether quantity can be promised, for example before \
                    createOrUpdateReservation; use getAvailabilityByProduct instead for a per-location breakdown, \
                    and listAvailabilityBySku only when a list-shaped response is required.
                    Preconditions: the SKU must have at least one stock-summary row; expired ACTIVE lots stay \
                    counted in on-hand but are subtracted from ATP.
                    Required inputs: productSku (string); locationId and storageLocationId (UUIDs) optionally \
                    narrow the scope (storageLocationId wins when both are given), sourceType (WAREHOUSE, SUPPLIER \
                    or TRANSIT) selects the lookup strategy, and horizon (ISO-8601 instant) bounds the forecast \
                    quantities; forecast supply is keyed by site, so a storage location is resolved to its parent \
                    site for the forecast fields.
                    No events are emitted and no state changes; ATP is on-hand minus hard allocations minus expired \
                    ACTIVE lot on-hand — soft reservations are not subtracted (ADR-0001).
                    Returns 404 when the SKU has no stock-summary rows, and 400 when locationId is combined with a \
                    non-WAREHOUSE sourceType (INVALID_PARAM_COMBINATION).
                    """,
            tags = {"Inventory Availability"})
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Availability view returned",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AvailabilityView.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid request parameters or locationId provided without sourceType=WAREHOUSE",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "User lacks required read permission",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Product SKU or location not found",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "500",
                        description = "Unexpected server error",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class)))
            })
    // Issue: CAP-215 Story #36
    public ResponseEntity<AvailabilityView> queryAvailabilityBySku(
            @Parameter(description = "Product SKU", required = true) @RequestParam String productSku,
            @Parameter(description = "Location identifier") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Storage location identifier (optional; narrows to sub-location)")
                    @RequestParam(required = false)
                    UUID storageLocationId,
            @Parameter(
                            description =
                                    "Inventory lookup strategy. WAREHOUSE = from physical location stock, SUPPLIER = from supplier lead time, TRANSIT = from in-transit supply. When sourceType is WAREHOUSE, locationId narrows to a specific location.")
                    @RequestParam(required = false)
                    InventorySourceType sourceType,
            @Parameter(
                            description =
                                    "Optional forecast horizon (ISO-8601 instant) bounding incomingQty/outgoingQty; "
                                            + "documents without an expected date are excluded from horizon-bounded results.")
                    @RequestParam(required = false)
                    java.time.Instant horizon) {
        log.info(
                "GET /v1/inventory/availability/by-sku productSku={} locationId={} sourceType={}",
                productSku,
                locationId,
                sourceType);
        return ResponseEntity.ok(resolveAvailability(productSku, locationId, storageLocationId, sourceType, horizon));
    }

    private AvailabilityView resolveAvailability(
            String sku,
            UUID locationId,
            UUID storageLocationId,
            InventorySourceType sourceType,
            java.time.Instant horizon) {
        validateLocationAndSourceType(locationId, sourceType);
        return availabilityService.queryAvailability(sku, locationId, storageLocationId, sourceType, horizon);
    }

    @GetMapping("/lead-time")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view", "inventory:on_hand:search"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "','"
            + InventoryPermissionRegistry.INVENTORY_SEARCH + "')")
    @Operation(
            operationId = "getInventoryLeadTime",
            summary = "Query product lead time",
            description = """
                    Returns a dynamic lead-time estimate (minDays, maxDays, display text, source and confidence) \
                    for a product, preferring the distributor inventory feed (source INVENTORY, confidence HIGH) \
                    and falling back to the manufacturer supply feed (source SUPPLY_CHAIN, confidence MEDIUM).
                    Use this tool to estimate replenishment timing when stock is short; use getAvailabilityBySku \
                    instead for on-hand and ATP quantities.
                    Preconditions: at least one normalized feed must carry lead-time data for the product.
                    Required inputs: productId (UUID); locationId and storageLocationId (UUIDs) are optional and \
                    are echoed into the response scope (storageLocationId wins) but do not filter the feed lookup, \
                    and sourceType follows the same WAREHOUSE-only rule as the availability reads.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no feed carries lead-time data for the product, and 400 when locationId is \
                    combined with a non-WAREHOUSE sourceType (INVALID_PARAM_COMBINATION).
                    """,
            tags = {"Inventory Availability"})
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Lead-time view returned",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = LeadTimeView.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid request parameters or locationId provided without sourceType=WAREHOUSE",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "User lacks required read permission",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Lead-time data not found",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "500",
                        description = "Unexpected server error",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class)))
            })
    public ResponseEntity<LeadTimeView> queryLeadTime(
            @Parameter(description = "Product identifier", required = true) @RequestParam UUID productId,
            @Parameter(description = "Location identifier") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Storage location identifier (optional; narrows to sub-location)")
                    @RequestParam(required = false)
                    UUID storageLocationId,
            @Parameter(
                            description =
                                    "Inventory lookup strategy. WAREHOUSE = from physical location stock, SUPPLIER = from supplier lead time, TRANSIT = from in-transit supply. When sourceType is WAREHOUSE, locationId narrows to a specific location.")
                    @RequestParam(required = false)
                    InventorySourceType sourceType) {
        validateLocationAndSourceType(locationId, sourceType);
        log.info(
                "GET /v1/inventory/availability/lead-time productId={} locationId={} storageLocationId={} sourceType={}",
                productId,
                locationId,
                storageLocationId,
                sourceType);
        return ResponseEntity.ok(inventoryLeadTimeService.queryLeadTime(productId, locationId, storageLocationId));
    }

    private void validateLocationAndSourceType(UUID locationId, InventorySourceType sourceType) {
        if (locationId != null && sourceType != null && sourceType != InventorySourceType.WAREHOUSE) {
            throw new InvalidParamCombinationException("locationId is only valid when sourceType is WAREHOUSE");
        }
    }

    /**
     * Not implemented by design.
     *
     * <p>
     * Availability is a derived projection computed from inventory ledger events,
     * not a mutable
     * record that can be overwritten directly. Accepting direct writes here would
     * bypass movement
     * validation, break auditability, and risk ATP inconsistencies.
     *
     * <p>
     * Use ledger-backed write APIs instead:
     * POST /v1/inventory/stock-movements
     * POST /v1/inventory/adjustments
     */
    @PostMapping("/{productId}")
    @EmitEvent(id = "INVENTORY_AVAILABILITY_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:create", "inventory:adjustment:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "','"
            + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "updateInventoryAvailability",
            summary = "Update inventory availability",
            description = """
                    Rejects direct availability writes with 501 NOT_IMPLEMENTED by design: availability is a \
                    projection derived from inventory ledger events, and overwriting it would bypass movement \
                    validation and break auditability.
                    Use this tool for nothing in production flows; record a movement with createStockMovement or \
                    raise a correction with createAdjustmentRequest (posted via approveAdjustmentRequest) instead.
                    Preconditions: none are evaluated; the request is rejected before any validation.
                    Required inputs: productId (UUID) path parameter; any request body is ignored.
                    Emits an INVENTORY_AVAILABILITY_UPDATE event recording the rejected attempt; no inventory \
                    state changes.
                    Returns 501 for every call.
                    """,
            tags = {"Inventory Availability"})
    @ApiResponse(
            responseCode = "200",
            description = "Availability updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = InventoryAvailabilityResponse.class)))
    @ApiResponse(responseCode = "501", description = "Not implemented")
    public ResponseEntity<InventoryAvailabilityResponse> updateInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Ignored; the endpoint rejects every call with 501 before reading the body.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Ignored body", value = "{}")))
                    @RequestBody(required = false)
                    Object requestBody) {
        log.info("POST /v1/inventory/availability/{}", productId);
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_IMPLEMENTED)
                .build();
    }
}
