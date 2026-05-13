package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.service.CatalogServiceImpl;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/catalog-items")
@Tag(name = "Catalog Items API", description = "API for managing catalog items by type")
public class CatalogItemController {

    private final CatalogServiceImpl catalogService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping("/{type}")
    @Operation(
            summary = "Add a new catalog item",
            description = "Adds a new product, service, or non-inventory product to the catalog.")
    @ApiResponse(
            responseCode = "201",
            description = "Catalog item created successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CatalogItemResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid item type or request body")
    @EmitEvent(id = "CATALOG_ITEM_CREATE", apiVersion = "1")
    public ResponseEntity<CatalogItemResponseDto> addCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @RequestBody CatalogItemRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCatalogItem(type, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PutMapping("/{type}/{catalogId}")
    @Operation(
            summary = "Update an existing catalog item",
            description = "Updates an existing product, service, or non-inventory product in the catalog.")
    @ApiResponse(
            responseCode = "200",
            description = "Catalog item updated successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CatalogItemResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid item type or request body")
    @ApiResponse(responseCode = "404", description = "Catalog item not found")
    @EmitEvent(id = "CATALOG_ITEM_UPDATE", apiVersion = "1")
    public ResponseEntity<CatalogItemResponseDto> updateCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @Parameter(description = "ID of the catalog item to update") @PathVariable UUID catalogId,
            @RequestBody CatalogItemRequestDto request) {
        return catalogService
                .updateCatalogItem(type, catalogId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_DELETE')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_DELETE"})
    @DeleteMapping("/{type}/{catalogId}")
    @Operation(
            summary = "Delete a catalog item",
            description = "Deletes a product, service, or non-inventory product from the catalog by its ID.")
    @ApiResponse(responseCode = "204", description = "Catalog item deleted successfully")
    @ApiResponse(responseCode = "400", description = "Invalid item type")
    @ApiResponse(responseCode = "404", description = "Catalog item not found")
    @EmitEvent(id = "CATALOG_ITEM_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @Parameter(description = "ID of the catalog item to delete") @PathVariable UUID catalogId) {
        return catalogService.deleteCatalogItem(type, catalogId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
