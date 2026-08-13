package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import com.positivity.catalog.service.SupplierItemCostService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/products/supplier-costs")
@Tag(name = "Supplier Item Cost API", description = "Manage supplier item costs with volume tiers")
public class SupplierItemCostController {

    private final SupplierItemCostService supplierItemCostService;

    public SupplierItemCostController(SupplierItemCostService supplierItemCostService) {
        this.supplierItemCostService = supplierItemCostService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:supplier_cost:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:supplier_cost:write"})
    @Operation(operationId = "createSupplierItemCost", summary = "Create Supplier Cost Structure", description = """
            Creates the cost structure one supplier charges for one item: a base cost plus optional volume \
            tiers, unique per supplier and item pair.
            Use this tool to record supplier purchasing costs; do not use updateStandardItemCost, which sets \
            the item's internal standard cost, and use updateSupplierItemCost to change a structure that \
            already exists.
            Preconditions: no cost structure may already exist for the supplier and item pair; tiers, when \
            supplied, must start at minQuantity 1, be contiguous and non-overlapping, and only the final \
            tier may leave maxQuantity null, which it must.
            Required inputs: supplierId (UUID), itemId (UUID) and a 3-letter currencyCode; baseCost is \
            optional but cannot be negative, and each tier needs minQuantity and a positive unitCost.
            Emits a CATALOG_SUPPLIER_COST_CREATE event; no item or catalog pricing records are touched.
            Returns 409 when a structure already exists for the supplier and item pair, and 400 when the \
            currency code is invalid or the tier structure violates the contiguity rules.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SupplierItemCostDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @ApiResponse(responseCode = "409", description = "Duplicate supplier and item combination")
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_CREATE", apiVersion = "1")
    public ResponseEntity<SupplierItemCostDto> createCostStructure(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cost structure for one supplier and item pair: base cost plus contiguous"
                                    + " volume tiers whose final tier is open-ended.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = SupplierItemCostCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Two-tier structure", value = """
                                                                    {"supplierId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "itemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "currencyCode":"USD","baseCost":10.50,
                                                                     "tiers":[
                                                                       {"minQuantity":1,"maxQuantity":49,"unitCost":10.50},
                                                                       {"minQuantity":50,"maxQuantity":null,"unitCost":9.25}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SupplierItemCostCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierItemCostService.createCostStructure(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:supplier_cost:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:supplier_cost:read"})
    @Operation(operationId = "getSupplierItemCost", summary = "Get Supplier Cost Structure", description = """
            Returns one supplier item cost structure with its base cost and volume tiers sorted by minimum \
            quantity.
            Use this tool when the cost structure id is already known; use listSupplierItemCosts instead to \
            find structures by itemId or supplierId.
            Preconditions: the cost structure must exist.
            Required inputs: id (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no cost structure exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SupplierItemCostDto.class)))
    @ApiResponse(responseCode = "404", description = "Not found")
    public ResponseEntity<SupplierItemCostDto> getCostStructure(@Parameter(required = true) @PathVariable UUID id) {
        return ResponseEntity.ok(supplierItemCostService.getCostStructure(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:supplier_cost:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:supplier_cost:write"})
    @Operation(operationId = "updateSupplierItemCost", summary = "Update Supplier Cost Structure", description = """
            Replaces a supplier item cost structure wholesale — supplier, item, currency, base cost and the \
            entire tier list are overwritten with the submitted values.
            Use this tool to reprice or re-tier an existing structure; do not use createSupplierItemCost, \
            which adds a structure for a pair that has none yet.
            Preconditions: the structure must exist, and repointing it at a different supplier and item pair \
            must not collide with another structure for that pair; tiers follow the same contiguity rules as \
            creation.
            Required inputs: id (UUID) path parameter plus supplierId, itemId and a 3-letter currencyCode; \
            the tier list submitted replaces the previous one entirely.
            Emits a CATALOG_SUPPLIER_COST_UPDATE event; no item or catalog pricing records are touched.
            Returns 404 when no cost structure exists for the supplied id, 409 when the supplier and item \
            pair collides with another structure, and 400 when the currency code or tier structure is \
            invalid.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SupplierItemCostDto.class)))
    @ApiResponse(responseCode = "404", description = "Not found")
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_UPDATE", apiVersion = "1")
    public ResponseEntity<SupplierItemCostDto> updateCostStructure(
            @Parameter(required = true) @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement of the structure; the submitted tier list overwrites"
                                    + " the stored tiers entirely.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = SupplierItemCostUpdateRequestDto.class),
                                            examples = @ExampleObject(name = "Reprice base cost", value = """
                                                                    {"supplierId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "itemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "currencyCode":"USD","baseCost":11.00,
                                                                     "tiers":[
                                                                       {"minQuantity":1,"maxQuantity":null,"unitCost":11.00}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SupplierItemCostUpdateRequestDto request) {
        return ResponseEntity.ok(supplierItemCostService.updateCostStructure(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:supplier_cost:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:supplier_cost:write"})
    @Operation(operationId = "deleteSupplierItemCost", summary = "Delete Supplier Cost Structure", description = """
            Permanently deletes a supplier item cost structure and its volume tiers; there is no soft delete \
            or history.
            Use this tool when a supplier no longer offers the item; use updateSupplierItemCost instead to \
            change prices while keeping the structure.
            Preconditions: the cost structure must exist.
            Required inputs: id (UUID) as a path parameter; there is no request body.
            Emits a CATALOG_SUPPLIER_COST_DELETE event; item costs recorded elsewhere are unaffected.
            Returns 204 on success, and 404 when no cost structure exists for the supplied id.
            """)
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Not found")
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteCostStructure(@Parameter(required = true) @PathVariable UUID id) {
        supplierItemCostService.deleteCostStructure(id);
        return ResponseEntity.noContent().build();
    }
}
