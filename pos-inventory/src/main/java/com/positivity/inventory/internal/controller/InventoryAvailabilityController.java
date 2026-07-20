package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.InventoryAvailabilityResponse;
import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.enums.InventorySourceType;
import com.positivity.inventory.internal.exception.InvalidParamCombinationException;
import com.positivity.inventory.internal.observability.BusinessSpanSupport;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import com.positivity.shared.error.ApiError;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
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

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-inventory");
    private static final String DOMAIN = "inventory";
    private static final String TEAM = "inventory-eng";

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
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @Operation(
            operationId = "getInventoryAvailability",
            summary = "Query inventory availability",
            description = "Returns per-location availability for a product.",
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
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId) {
        Span span = TRACER.spanBuilder("Check Inventory Availability").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Check Inventory Availability");
        span.setAttribute("app.operation.type", "query");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            List<LocationAvailabilityDto> result = availabilityService.getAvailabilityByProduct(productId);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Checked inventory availability for product {}", productId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view", "inventory:on_hand:search"})
    @PreAuthorize("hasAnyAuthority('inventory:on_hand:view','inventory:on_hand:search')")
    @Operation(
            operationId = "queryAvailabilityBySkuList",
            summary = "Query inventory availability by SKU (list form)",
            description =
                    "Returns on-hand, allocated, and available-to-promise quantities for a product at a specific location, wrapped in a list. Accepts 'sku' as the query param name.",
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
                    InventorySourceType sourceType) {
        log.info("GET /v1/inventory/availability sku={} locationId={} sourceType={}", sku, locationId, sourceType);
        return ResponseEntity.ok(List.of(resolveAvailability(sku, locationId, storageLocationId, sourceType)));
    }

    @GetMapping("/by-sku")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view", "inventory:on_hand:search"})
    @PreAuthorize("hasAnyAuthority('inventory:on_hand:view','inventory:on_hand:search')")
    @Operation(
            operationId = "listAvailabilityBySku",
            summary = "Query inventory availability by SKU and location",
            description =
                    "Returns on-hand, allocated, and available-to-promise quantities for a product at a specific location. storageLocationId is optional to narrow the scope to a sub-location.",
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
                    InventorySourceType sourceType) {
        log.info(
                "GET /v1/inventory/availability/by-sku productSku={} locationId={} sourceType={}",
                productSku,
                locationId,
                sourceType);
        return ResponseEntity.ok(resolveAvailability(productSku, locationId, storageLocationId, sourceType));
    }

    private AvailabilityView resolveAvailability(
            String sku, UUID locationId, UUID storageLocationId, InventorySourceType sourceType) {
        validateLocationAndSourceType(locationId, sourceType);
        return availabilityService.queryAvailability(sku, locationId, storageLocationId, sourceType);
    }

    @GetMapping("/lead-time")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view", "inventory:on_hand:search"})
    @PreAuthorize("hasAnyAuthority('inventory:on_hand:view','inventory:on_hand:search')")
    @Operation(
            operationId = "getLeadTime",
            summary = "Query product lead time",
            description = "Returns dynamic lead-time estimate for a product at a location.",
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
    @PreAuthorize("hasAnyAuthority('inventory:adjustment:create','inventory:adjustment:approve')")
    @Operation(
            summary = "Update inventory availability",
            description =
                    "Not implemented by design. Availability is derived from ledger events and is read-only via this endpoint. "
                            + "Use POST /v1/inventory/stock-movements or POST /v1/inventory/adjustments for inventory changes.",
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
            @RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/inventory/availability/{}", productId);
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_IMPLEMENTED)
                .build();
    }
}
