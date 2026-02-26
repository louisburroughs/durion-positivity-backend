package com.positivity.catalog.internal.controller;

import java.util.List;
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

import com.positivity.catalog.internal.dto.CatalogDto;
import com.positivity.catalog.service.CatalogService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/catalogs")
@Tag(name = "Catalog API", description = "API for managing catalogs")
public class CatalogController {

    private final CatalogService catalogService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/{catalogId}")
    @Operation(summary = "Get a catalog by ID", description = "Retrieves a specific catalog by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved catalog", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "404", description = "Catalog not found")
    public ResponseEntity<CatalogDto> getCatalogById(
            @Parameter(description = "ID of the catalog to be obtained") @PathVariable UUID catalogId) {
        return catalogService.getCatalogById(catalogId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/name/{name}")
    @Operation(summary = "Get catalogs by name", description = "Retrieves a list of catalogs matching the given name.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved catalogs", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    public List<CatalogDto> getCatalogByName(
            @Parameter(description = "Name of the catalogs to be obtained") @PathVariable String name) {
        return catalogService.getCatalogsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping
    @Operation(summary = "Add a new catalog", description = "Adds a new catalog.")
    @ApiResponse(responseCode = "201", description = "Catalog created successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @EmitEvent(id = "CATALOG_CATALOG_CREATE", apiVersion = "1")
    public ResponseEntity<CatalogDto> addCatalog(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Catalog object to be added", required = true, content = @Content(schema = @Schema(implementation = CatalogDto.class))) @RequestBody CatalogDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCatalog(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/{catalogId}")
    @Operation(summary = "Update an existing catalog", description = "Updates an existing catalog.")
    @ApiResponse(responseCode = "200", description = "Catalog updated successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Catalog not found")
    @EmitEvent(id = "CATALOG_CATALOG_UPDATE", apiVersion = "1")
    public ResponseEntity<CatalogDto> updateCatalog(
            @Parameter(description = "ID of the catalog to update") @PathVariable UUID catalogId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated catalog object", required = true, content = @Content(schema = @Schema(implementation = CatalogDto.class))) @RequestBody CatalogDto request) {
        return catalogService.updateCatalog(catalogId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_DELETE')")
    @DeleteMapping("/{catalogId}")
    @Operation(summary = "Delete a catalog", description = "Deletes a catalog by its ID.")
    @ApiResponse(responseCode = "204", description = "Catalog deleted successfully")
    @ApiResponse(responseCode = "404", description = "Catalog not found")
    @EmitEvent(id = "CATALOG_CATALOG_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteCatalog(
            @Parameter(description = "ID of the catalog to delete") @PathVariable UUID catalogId) {
        return catalogService.deleteCatalog(catalogId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
