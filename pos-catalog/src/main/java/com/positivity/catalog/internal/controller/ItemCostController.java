package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ItemCostAuditDto;
import com.positivity.catalog.internal.dto.ItemCostsDto;
import com.positivity.catalog.internal.dto.UpdateStandardCostRequestDto;
import com.positivity.catalog.service.ItemCostService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/products/items")
@Tag(name = "Item Cost API", description = "Manage standard, last, and average item costs")
public class ItemCostController {

    private final ItemCostService itemCostService;

    public ItemCostController(ItemCostService itemCostService) {
        this.itemCostService = itemCostService;
    }

    @PutMapping("/{itemId}/standard-cost")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasAuthority('inventory.cost.standard.update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_MANAGER", "inventory.cost.standard.update"})
    @Operation(summary = "Update standard item cost")
    @ApiResponse(
            responseCode = "200",
            description = "Updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ItemCostsDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @EmitEvent(id = "CATALOG_ITEM_COST_STANDARD_UPDATE", apiVersion = "1")
    public ResponseEntity<ItemCostsDto> updateStandardCost(
            @Parameter(required = true) @PathVariable UUID itemId,
            @Valid @RequestBody UpdateStandardCostRequestDto request) {
        return ResponseEntity.ok(itemCostService.updateStandardCost(itemId, request));
    }

    @GetMapping("/{itemId}/costs")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CATALOG_VIEW"})
    @Operation(summary = "Get current item costs")
    @ApiResponse(
            responseCode = "200",
            description = "Found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ItemCostsDto.class)))
    public ResponseEntity<ItemCostsDto> getItemCosts(@Parameter(required = true) @PathVariable UUID itemId) {
        return ResponseEntity.ok(itemCostService.getItemCosts(itemId));
    }

    @GetMapping("/{itemId}/costs/audit")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CATALOG_VIEW"})
    @Operation(summary = "Get item cost audit history")
    @ApiResponse(
            responseCode = "200",
            description = "Found",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = ItemCostAuditDto.class)))
    public ResponseEntity<List<ItemCostAuditDto>> getAuditHistory(
            @Parameter(required = true) @PathVariable UUID itemId) {
        return ResponseEntity.ok(itemCostService.getAuditHistory(itemId));
    }
}
