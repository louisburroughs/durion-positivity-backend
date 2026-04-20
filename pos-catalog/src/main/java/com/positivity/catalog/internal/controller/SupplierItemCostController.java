package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import com.positivity.catalog.service.SupplierItemCostService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
    @Operation(summary = "Create supplier cost structure")
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @ApiResponse(responseCode = "409", description = "Duplicate supplier and item combination")
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_CREATE", apiVersion = "1")
    public ResponseEntity<SupplierItemCostDto> createCostStructure(
            @Valid @RequestBody SupplierItemCostCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierItemCostService.createCostStructure(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:supplier_cost:read')")
    @Operation(summary = "Get supplier cost structure")
    @ApiResponse(responseCode = "200", description = "Found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostDto.class)))
    @ApiResponse(responseCode = "404", description = "Not found")
    public ResponseEntity<SupplierItemCostDto> getCostStructure(@Parameter(required = true) @PathVariable UUID id) {
        return ResponseEntity.ok(supplierItemCostService.getCostStructure(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:supplier_cost:write')")
    @Operation(summary = "Update supplier cost structure")
    @ApiResponse(responseCode = "200", description = "Updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostDto.class)))
    @ApiResponse(responseCode = "404", description = "Not found")
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_UPDATE", apiVersion = "1")
    public ResponseEntity<SupplierItemCostDto> updateCostStructure(
            @Parameter(required = true) @PathVariable UUID id,
            @Valid @RequestBody SupplierItemCostUpdateRequestDto request) {
        return ResponseEntity.ok(supplierItemCostService.updateCostStructure(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:supplier_cost:write')")
    @Operation(summary = "Delete supplier cost structure")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Not found")
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteCostStructure(@Parameter(required = true) @PathVariable UUID id) {
        supplierItemCostService.deleteCostStructure(id);
        return ResponseEntity.noContent().build();
    }
}
