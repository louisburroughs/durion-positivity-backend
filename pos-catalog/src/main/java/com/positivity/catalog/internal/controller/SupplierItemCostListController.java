package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import com.positivity.catalog.service.SupplierItemCostService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/catalog/supplier-item-costs")
@Tag(name = "Supplier Item Cost List API", description = "Query supplier item cost structures")
public class SupplierItemCostListController {

    private final SupplierItemCostService supplierItemCostService;

    public SupplierItemCostListController(SupplierItemCostService supplierItemCostService) {
        this.supplierItemCostService = supplierItemCostService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:supplier_cost:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:supplier_cost:read"})
    @EmitEvent(id = "CATALOG_SUPPLIER_COST_LIST", apiVersion = "1")
    @Operation(operationId = "listSupplierItemCosts", summary = "List Supplier Cost Structures", description = """
            Returns a page of supplier item cost structures filtered by item, by supplier, or by both \
            combined with AND semantics.
            Use this tool to find which suppliers price an item or which items a supplier prices; use \
            getSupplierItemCost instead when the structure id is already known.
            Preconditions: at least one of itemId or supplierId must be supplied; an unfiltered listing is \
            deliberately not offered.
            Required inputs: itemId or supplierId (UUIDs) as query parameters, plus standard Spring page, \
            size and sort parameters.
            Emits a CATALOG_SUPPLIER_COST_LIST audit event; no cost data changes.
            Returns 400 when neither itemId nor supplierId is provided, and 200 with an empty page when the \
            filters match nothing.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Page.class)))
    @ApiResponse(responseCode = "400", description = "At least one filter is required")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<Page<SupplierItemCostDto>> listCostStructures(
            @Parameter(description = "Filter by catalog item ID") @RequestParam(required = false) UUID itemId,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) UUID supplierId,
            Pageable pageable) {
        return ResponseEntity.ok(supplierItemCostService.listCostStructures(itemId, supplierId, pageable));
    }
}
