package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CatalogDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.CatalogService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
@RequestMapping("/v1/catalogs")
@Tag(name = "Catalog API", description = "API for managing catalogs")
public class CatalogController {

    private final CatalogService catalogService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.CATALOG_GROUPING_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.CATALOG_GROUPING_VIEW})
    @GetMapping("/{catalogId}")
    @Operation(operationId = "getCatalogById", summary = "Get a Catalog by ID", description = """
            Returns one named catalog with the ids of the products, services and non-inventory products it groups.
            Use this tool when the catalogId is already known; use listCatalogsByName instead to find catalogs by \
            a name fragment.
            Preconditions: the catalog must exist.
            Required inputs: catalogId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no catalog exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved catalog",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "404", description = "Catalog not found")
    public ResponseEntity<CatalogDto> getCatalogById(
            @Parameter(description = "ID of the catalog to be obtained") @PathVariable UUID catalogId) {
        return catalogService
                .getCatalogById(catalogId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.CATALOG_GROUPING_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.CATALOG_GROUPING_VIEW})
    @GetMapping("/name/{name}")
    @Operation(operationId = "listCatalogsByName", summary = "List Catalogs by Name", description = """
            Returns every catalog whose name contains the supplied fragment, matched case-insensitively.
            Use this tool to discover a catalogId by name; use getCatalogById instead when the id is already known.
            Preconditions: none; an empty result simply means no catalog name contains the fragment.
            Required inputs: name (path parameter) as a case-insensitive substring; there is no paging and no \
            request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when nothing matches, so an empty result is not an error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved catalogs",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    public List<CatalogDto> getCatalogByName(
            @Parameter(description = "Name of the catalogs to be obtained") @PathVariable String name) {
        return catalogService.getCatalogsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.CATALOG_GROUPING_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.CATALOG_GROUPING_EDIT})
    @PostMapping
    @Operation(operationId = "createCatalog", summary = "Create a New Catalog", description = """
            Creates a named catalog that groups existing products, services and non-inventory products for \
            presentation; the catalog does not own the items, it only references them.
            Use this tool to define a new grouping; do not use updateCatalog, which replaces a catalog that \
            already exists, and do not use createCatalogItem, which creates the underlying items themselves.
            Preconditions: any ids listed in productIds, serviceIds or nonInventoryProductIds should already \
            exist; ids that do not resolve are silently dropped from the stored catalog rather than rejected.
            Required inputs: name; description and the three id lists are optional and default to empty.
            Emits a CATALOG_CATALOG_CREATE event; no product, service or non-inventory records are modified.
            Returns 201 with the stored catalog, whose id lists reflect only the references that resolved, so \
            callers should compare them against what was sent.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Catalog created successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @EmitEvent(id = "CATALOG_CATALOG_CREATE", apiVersion = "1")
    public ResponseEntity<CatalogDto> addCatalog(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Catalog to create: a name plus optional lists of existing product, service and"
                                            + " non-inventory product ids to group.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CatalogDto.class),
                                            examples = @ExampleObject(name = "Catalog with two products", value = """
                                                                    {"name":"Winter Tires",
                                                                     "description":"Seasonal winter tire lineup",
                                                                     "productIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                                   "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"],
                                                                     "serviceIds":[],
                                                                     "nonInventoryProductIds":[]}
                                                                    """)))
                    @RequestBody
                    CatalogDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCatalog(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.CATALOG_GROUPING_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.CATALOG_GROUPING_EDIT})
    @PutMapping("/{catalogId}")
    @Operation(operationId = "updateCatalog", summary = "Update an Existing Catalog", description = """
            Replaces a catalog's name, description and full membership lists with the supplied values; this is a \
            full replacement, not a merge, so omitted item ids are removed from the catalog.
            Use this tool to rename a catalog or change which items it groups; do not use createCatalog, which \
            adds a new catalog, and do not use updateCatalogItem, which edits the items themselves.
            Preconditions: the catalog must exist; ids in the three membership lists that do not resolve are \
            silently dropped rather than rejected.
            Required inputs: catalogId (UUID) path parameter and the complete replacement body including name; \
            an omitted id list clears that membership.
            Emits a CATALOG_CATALOG_UPDATE event; the referenced items themselves are not modified.
            Returns 404 when no catalog exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Catalog updated successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Catalog not found")
    @EmitEvent(id = "CATALOG_CATALOG_UPDATE", apiVersion = "1")
    public ResponseEntity<CatalogDto> updateCatalog(
            @Parameter(description = "ID of the catalog to update") @PathVariable UUID catalogId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete replacement state for the catalog; membership lists overwrite the"
                                    + " existing ones rather than merging.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CatalogDto.class),
                                            examples =
                                                    @ExampleObject(name = "Rename and reduce membership", value = """
                                                                    {"name":"All-Season Tires",
                                                                     "description":"Renamed lineup",
                                                                     "productIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"],
                                                                     "serviceIds":[],
                                                                     "nonInventoryProductIds":[]}
                                                                    """)))
                    @RequestBody
                    CatalogDto request) {
        return catalogService
                .updateCatalog(catalogId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.CATALOG_GROUPING_DELETE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.CATALOG_GROUPING_DELETE})
    @DeleteMapping("/{catalogId}")
    @Operation(operationId = "deleteCatalog", summary = "Delete a Catalog", description = """
            Permanently deletes a catalog grouping; the products, services and non-inventory products it \
            referenced are untouched and remain available.
            Use this tool to retire a grouping that is no longer needed; do not use deleteCatalogItem, which \
            deletes the underlying items themselves.
            Preconditions: the catalog must exist; there is no soft delete or archive state, so the row is \
            removed outright.
            Required inputs: catalogId (UUID) as a path parameter; there is no request body.
            Emits a CATALOG_CATALOG_DELETE event; member items are not cascaded.
            Returns 204 when the catalog is removed, and 404 when no catalog exists for the supplied id.
            """)
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
