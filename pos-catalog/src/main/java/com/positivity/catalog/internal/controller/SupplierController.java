package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostResponseDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import com.positivity.catalog.service.SupplierItemCostService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/suppliers")
@Tag(name = "Supplier API", description = "API for supplier item cost structures")
public class SupplierController {

    private final SupplierItemCostService supplierItemCostService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/costs/items")
    @Operation(summary = "Create supplier-item cost tiers", description = "Creates a supplier-item cost structure with optional volume-based tiers.")
    @ApiResponse(responseCode = "201", description = "Supplier-item cost structure created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload or tier structure")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "CATALOG_SUPPLIER_ITEM_COST_CREATE", apiVersion = "1")
    public ResponseEntity<SupplierItemCostResponseDto> createSupplierItemCost(
            @RequestBody SupplierItemCostCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierItemCostService.createSupplierItemCost(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/{supplierId}/items/{itemId}/costs")
    @Operation(summary = "Get supplier-item cost tiers", description = "Gets the supplier-item cost structure and ordered quantity tiers.")
    @ApiResponse(responseCode = "200", description = "Supplier-item cost found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Supplier-item cost not found")
    public ResponseEntity<SupplierItemCostResponseDto> getSupplierItemCost(
            @Parameter(description = "Supplier ID", required = true) @PathVariable UUID supplierId,
            @Parameter(description = "Item ID", required = true) @PathVariable UUID itemId) {
        return ResponseEntity.ok(supplierItemCostService.getSupplierItemCost(supplierId, itemId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/{supplierId}/items/{itemId}/costs")
    @Operation(summary = "Update supplier-item cost tiers", description = "Updates currency, base cost, and quantity-based tiers for an existing supplier-item cost structure.")
    @ApiResponse(responseCode = "200", description = "Supplier-item cost updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload or tier structure")
    @ApiResponse(responseCode = "404", description = "Supplier-item cost not found")
    @ApiResponse(responseCode = "409", description = "Update conflict")
    @EmitEvent(id = "CATALOG_SUPPLIER_ITEM_COST_UPDATE", apiVersion = "1")
    public ResponseEntity<SupplierItemCostResponseDto> updateSupplierItemCost(
            @Parameter(description = "Supplier ID", required = true) @PathVariable UUID supplierId,
            @Parameter(description = "Item ID", required = true) @PathVariable UUID itemId,
            @RequestBody SupplierItemCostUpdateRequestDto request) {
        return ResponseEntity.ok(supplierItemCostService.updateSupplierItemCost(supplierId, itemId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_DELETE')")
    @DeleteMapping("/{supplierId}/items/{itemId}/costs")
    @Operation(summary = "Delete supplier-item cost tiers", description = "Deletes the supplier-item cost structure and all associated tiers.")
    @ApiResponse(responseCode = "204", description = "Supplier-item cost deleted")
    @ApiResponse(responseCode = "404", description = "Supplier-item cost not found")
    @EmitEvent(id = "CATALOG_SUPPLIER_ITEM_COST_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteSupplierItemCost(
            @Parameter(description = "Supplier ID", required = true) @PathVariable UUID supplierId,
            @Parameter(description = "Item ID", required = true) @PathVariable UUID itemId) {
        supplierItemCostService.deleteSupplierItemCost(supplierId, itemId);
        return ResponseEntity.noContent().build();
    }
}
