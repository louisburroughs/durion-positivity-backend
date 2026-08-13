package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.service.CatalogServiceImpl;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @Operation(operationId = "createCatalogItem", summary = "Add a New Catalog Item", description = """
            Creates a catalog item of the given type: a product, a service, or a non-inventory product.
            Use this tool for quick item entry across the three item types; do not use createProduct, which is \
            the richer product-master path that enforces SKU and manufacturer-part uniqueness — this endpoint \
            performs no duplicate checks.
            Preconditions: none; for type service and noninventory only name, shortDescription and \
            longDescription are persisted, while type product also stores manufacturer, SKU, productCode, \
            material, color, warranty and specifications fields.
            Required inputs: type path parameter, one of product, service or noninventory (case-insensitive), \
            plus a body with at least name.
            Emits a CATALOG_ITEM_CREATE event; no catalog groupings are touched.
            Returns 400 when the type path parameter is not one of the three supported values.
            """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Item to create; only name, shortDescription and longDescription are stored"
                                    + " for services and non-inventory products, products store the full field set.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CatalogItemRequestDto.class),
                                            examples = @ExampleObject(name = "New product item", value = """
                                                                    {"name":"Premium Wiper Blade 22in",
                                                                     "shortDescription":"22-inch beam wiper blade",
                                                                     "longDescription":"All-weather beam wiper blade, 22 inch",
                                                                     "sku":"WIP-22-PREM",
                                                                     "productCode":"036121960221",
                                                                     "manufacturerPartNumber":"PWB-22",
                                                                     "manufacturerName":"ClearView",
                                                                     "material":"Rubber","color":"Black"}
                                                                    """)))
                    @RequestBody
                    CatalogItemRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCatalogItem(type, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PutMapping("/{type}/{catalogId}")
    @Operation(operationId = "updateCatalogItem", summary = "Update an Existing Catalog Item", description = """
            Replaces a catalog item of the given type with the supplied state; fields omitted from the body are \
            overwritten with null, so this is a full replacement, not a patch.
            Use this tool to edit an item created through createCatalogItem; do not use updateProduct, which is \
            the product-master path that protects SKU immutability — this endpoint applies no such guard.
            Preconditions: an item of the given type must exist under the supplied id; the type determines which \
            repository is consulted, so a product id passed with type service resolves to 404.
            Required inputs: type (product, service or noninventory, case-insensitive), catalogId (UUID) and the \
            complete replacement body.
            Emits a CATALOG_ITEM_UPDATE event; no catalog groupings are touched.
            Returns 404 when no item of that type exists for the supplied id, and 400 when the type is not one \
            of the three supported values.
            """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete replacement state for the item; omitted fields are cleared"
                                    + " because the update overwrites the whole row.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CatalogItemRequestDto.class),
                                            examples = @ExampleObject(name = "Rename a service", value = """
                                                                    {"name":"Tire Rotation and Balance",
                                                                     "shortDescription":"Rotate and balance four tires",
                                                                     "longDescription":"Rotate all four tires and balance each wheel"}
                                                                    """)))
                    @RequestBody
                    CatalogItemRequestDto request) {
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
    @Operation(operationId = "deleteCatalogItem", summary = "Delete a Catalog Item", description = """
            Permanently deletes a product, service or non-inventory product row by id; this is a hard delete \
            with no archive state.
            Use this tool to remove an item outright; use updateProductLifecycle instead when a product should \
            stop selling but keep its history, and use deleteCatalog to remove a grouping rather than its items.
            Preconditions: an item of the given type must exist under the supplied id.
            Required inputs: type (product, service or noninventory, case-insensitive) and catalogId (UUID) as \
            path parameters; there is no request body.
            Emits a CATALOG_ITEM_DELETE event; catalogs that referenced the item are not rewritten by this call.
            Returns 204 when the item is removed, 404 when no item of that type exists for the supplied id, and \
            400 when the type is not one of the three supported values.
            """)
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
