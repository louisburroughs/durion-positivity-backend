package com.positivity.catalog.controller;

import com.positivity.catalog.dao.CatalogDao;
import com.positivity.catalog.model.ProductEntity;
import com.positivity.catalog.model.ServiceEntity;
import com.positivity.catalog.model.NonInventoryProductEntity;
import com.positivity.catalog.model.CatalogEntity;
import com.positivity.catalog.CatalogItem;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
import java.util.Optional;


/**
 * Controller for managing catalog-related operations.
 * Provides endpoints for retrieving, adding, updating, and deleting catalog items.
 */

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/catalog")
@Tag(name = "Catalog API", description = "API for managing catalog items")
public class CatalogController {
    public static final String UNSUPPORTED_ITEM_TYPE = "Unsupported item type: {}";
    public static final String PRODUCT = "product";
    public static final String SERVICE = "service";
    public static final String NONINVENTORY = "noninventory";
    private final CatalogDao catalogDao;

    /**
     * Retrieves a product by its ID.
     * @param id The ID of the product.
     * @return ResponseEntity containing the product or a 404 status if not found.
     */
    
    @EmitEvent(id = "Catalog-000001-0000000001")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/product/id/{id}")
    @Operation(summary = "Get a product by ID", description = "Retrieves a specific product by its unique ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved product",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductEntity.class))),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public ResponseEntity<ProductEntity> getProductById(@Parameter(description = "ID of the product to be obtained") @PathVariable Long id) {
        Optional<ProductEntity> product = catalogDao.findProductById(id);
        return product.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Retrieves products by their name.
     * @param name The name of the products.
     * @return List of products matching the name.
     */
    @EmitEvent(id = "Catalog-000001-0000000002")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/product/name/{name}")
    @Operation(summary = "Get products by name", description = "Retrieves a list of products matching the given name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved products",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductEntity.class)))
    })
    public List<ProductEntity> getProductByName(@Parameter(description = "Name of the products to be obtained") @PathVariable String name) {
        return catalogDao.findProductByName(name);
    }

    /**
     * Retrieves a service by its ID.
     * @param id The ID of the service.
     * @return ResponseEntity containing the service or a 404 status if not found.
     */
    @EmitEvent(id = "Catalog-000001-0000000003")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/service/id/{id}")
    @Operation(summary = "Get a service by ID", description = "Retrieves a specific service by its unique ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved service",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceEntity.class))),
            @ApiResponse(responseCode = "404", description = "Service not found")
    })
    public ResponseEntity<ServiceEntity> getServiceById(@Parameter(description = "ID of the service to be obtained") @PathVariable Long id) {
        Optional<ServiceEntity> service = catalogDao.findServiceById(id);
        return service.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Retrieves services by their name.
     * @param name The name of the services.
     * @return List of services matching the name.
     */
    @EmitEvent(id = "Catalog-000001-0000000004")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/service/name/{name}")
    @Operation(summary = "Get services by name", description = "Retrieves a list of services matching the given name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved services",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceEntity.class)))
    })
    public List<ServiceEntity> getServiceByName(@Parameter(description = "Name of the services to be obtained") @PathVariable String name) {
        return catalogDao.findServiceByName(name);
    }

    /**
     * Retrieves a non-inventory product by its ID.
     * @param id The ID of the non-inventory product.
     * @return ResponseEntity containing the non-inventory product or a 404 status if not found.
     */
    @EmitEvent(id = "Catalog-000001-0000000005")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/noninventory/id/{id}")
    @Operation(summary = "Get a non-inventory product by ID", description = "Retrieves a specific non-inventory product by its unique ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved non-inventory product",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = NonInventoryProductEntity.class))),
            @ApiResponse(responseCode = "404", description = "Non-inventory product not found")
    })
    public ResponseEntity<NonInventoryProductEntity> getNonInventoryProductById(@Parameter(description = "ID of the non-inventory product to be obtained") @PathVariable Long id) {
        Optional<NonInventoryProductEntity> item = catalogDao.findNonInventoryProductById(id);
        return item.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Retrieves non-inventory products by their name.
     * @param name The name of the non-inventory products.
     * @return List of non-inventory products matching the name.
     */
    @EmitEvent(id = "Catalog-000001-0000000006")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/noninventory/name/{name}")
    @Operation(summary = "Get non-inventory products by name", description = "Retrieves a list of non-inventory products matching the given name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved non-inventory products",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = NonInventoryProductEntity.class)))
    })
    public List<NonInventoryProductEntity> getNonInventoryProductByName(@Parameter(description = "Name of the non-inventory products to be obtained") @PathVariable String name) {
        return catalogDao.findNonInventoryProductByName(name);
    }

    /**
     * Retrieves a catalog by its ID.
     * @param id The ID of the catalog.
     * @return ResponseEntity containing the catalog or a 404 status if not found.
     */
    @EmitEvent(id = "Catalog-000001-0000000007")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/catalog/id/{id}")
    @Operation(summary = "Get a catalog by ID", description = "Retrieves a specific catalog by its unique ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved catalog",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogEntity.class))),
            @ApiResponse(responseCode = "404", description = "Catalog not found")
    })
    public ResponseEntity<CatalogEntity> getCatalogById(@Parameter(description = "ID of the catalog to be obtained") @PathVariable Long id) {
        Optional<CatalogEntity> catalog = catalogDao.findCatalogById(id);
        return catalog.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Retrieves catalogs by their name.
     * @param name The name of the catalogs.
     * @return List of catalogs matching the name.
     */
    @EmitEvent(id = "Catalog-000001-0000000008")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/catalog/name/{name}")
    @Operation(summary = "Get catalogs by name", description = "Retrieves a list of catalogs matching the given name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved catalogs",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogEntity.class)))
    })
    public List<CatalogEntity> getCatalogByName(@Parameter(description = "Name of the catalogs to be obtained") @PathVariable String name) {
        return catalogDao.findCatalogByName(name);
    }

    /**
     * Adds a new catalog item.
     * @param item The catalog item to add.
     * @param type The type of the catalog item (product, service, noninventory).
     * @return ResponseEntity containing the created item or an error status.
     */
    @EmitEvent(id = "Catalog-000001-0000000009")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/{type}")
    @Operation(summary = "Add a new catalog item", description = "Adds a new product, service, or non-inventory product to the catalog.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Catalog item created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid item type or request body")
    })
    public ResponseEntity<?> addCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @RequestBody CatalogItem item) { // Swapped order for clarity with @RequestBody
        switch (type.toLowerCase()) {
            case PRODUCT:
                if (item instanceof ProductEntity productEntity) {
                    ProductEntity createdProduct = catalogDao.saveProduct(productEntity);
                    return ResponseEntity.status(201).body(createdProduct);
                }
                break;
            case SERVICE:
                if (item instanceof ServiceEntity serviceEntity) {
                    ServiceEntity createdService = catalogDao.saveService(serviceEntity);
                    return ResponseEntity.status(201).body(createdService);
                }
                break;
            case NONINVENTORY:
                if (item instanceof NonInventoryProductEntity nonInventoryProductEntity) {
                    NonInventoryProductEntity createdNonInventoryProduct = catalogDao.saveNonInventoryProduct(nonInventoryProductEntity);
                    return ResponseEntity.status(201).body(createdNonInventoryProduct);
                }
                break;
            default:
                log.error(UNSUPPORTED_ITEM_TYPE, type);
                return ResponseEntity.badRequest().body(UNSUPPORTED_ITEM_TYPE);
        }
        return ResponseEntity.badRequest().body("Mismatched item type and payload");
    }


    /**
     * Updates an existing catalog item.
     * @param id The ID of the item to update.
     * @param item The updated catalog item details.
     * @param type The type of the catalog item.
     * @return ResponseEntity containing the updated item or an error status.
     */
    @EmitEvent(id = "Catalog-000001-0000000010")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/{type}/{id}")
    @Operation(summary = "Update an existing catalog item", description = "Updates an existing product, service, or non-inventory product in the catalog.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Catalog item updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid item type or request body"),
            @ApiResponse(responseCode = "404", description = "Catalog item not found")
    })
    public ResponseEntity<?> updateCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @Parameter(description = "ID of the catalog item to update") @PathVariable Long id, // Changed to Long
            @RequestBody CatalogItem item) { // Swapped order
        switch (type.toLowerCase()) {
            case PRODUCT:
                if (item instanceof ProductEntity productEntity) {
                    ProductEntity updatedProduct = catalogDao.updateProduct(id, productEntity);
                    return updatedProduct != null ? ResponseEntity.ok(updatedProduct) : ResponseEntity.notFound().build();
                }
                break;
            case SERVICE:
                if (item instanceof ServiceEntity serviceEntity) {
                    ServiceEntity updatedService = catalogDao.updateService(id, serviceEntity);
                    return updatedService != null ? ResponseEntity.ok(updatedService) : ResponseEntity.notFound().build();
                }
                break;
            case NONINVENTORY:
                if (item instanceof NonInventoryProductEntity nonInventoryProductEntity) {
                    NonInventoryProductEntity updatedNonInventory = catalogDao.updateNonInventoryProduct(id, nonInventoryProductEntity);
                    return updatedNonInventory != null ? ResponseEntity.ok(updatedNonInventory) : ResponseEntity.notFound().build();
                }
                break;
            default:
                log.error(UNSUPPORTED_ITEM_TYPE, type);
                return ResponseEntity.badRequest().body(UNSUPPORTED_ITEM_TYPE);
        }
        return ResponseEntity.badRequest().body("Mismatched item type and payload");
    }

    /**
     * Deletes a catalog item by its ID.
     * @param id The ID of the item to delete.
     * @param type The type of the catalog item.
     * @return ResponseEntity indicating success or failure.
     */
    @EmitEvent(id = "Catalog-000001-0000000011")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_DELETE')")
    @DeleteMapping("/{type}/{id}")
    @Operation(summary = "Delete a catalog item", description = "Deletes a product, service, or non-inventory product from the catalog by its ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Catalog item deleted successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid item type"),
            @ApiResponse(responseCode = "404", description = "Catalog item not found")
    })
    public ResponseEntity<Void> deleteCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @Parameter(description = "ID of the catalog item to delete") @PathVariable Long id) { // Changed to Long
        boolean deleted;
        switch (type.toLowerCase()) {
            case PRODUCT:
                deleted = catalogDao.deleteProduct(id);
                break;
            case SERVICE:
                deleted = catalogDao.deleteService(id);
                break;
            case NONINVENTORY:
                deleted = catalogDao.deleteNonInventoryProduct(id);
                break;
            default:
                log.error(UNSUPPORTED_ITEM_TYPE, type);
                return ResponseEntity.badRequest().build();
        }
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Adds a new catalog.
     * @param catalogEntity The catalog to add.
     * @return ResponseEntity containing the created catalog or an error status.
     */
    @EmitEvent(id = "Catalog-000001-0000000012")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/catalog")
    @Operation(summary = "Add a new catalog", description = "Adds a new catalog.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Catalog created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogEntity.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<CatalogEntity> addCatalog(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Catalog object to be added", required = true, content = @Content(schema = @Schema(implementation = CatalogEntity.class))) @RequestBody CatalogEntity catalogEntity) {
        CatalogEntity createdCatalog = catalogDao.saveCatalog(catalogEntity);
        return ResponseEntity.status(201).body(createdCatalog);
    }

    /**
     * Updates an existing catalog.
     * @param id The ID of the catalog to update.
     * @param catalogEntity The updated catalog details.
     * @return ResponseEntity containing the updated catalog or an error status.
     */
    @EmitEvent(id = "Catalog-000001-0000000013")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/catalog/{id}")
    @Operation(summary = "Update an existing catalog", description = "Updates an existing catalog.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Catalog updated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogEntity.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "404", description = "Catalog not found")
    })
    public ResponseEntity<CatalogEntity> updateCatalog(
            @Parameter(description = "ID of the catalog to update") @PathVariable Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated catalog object", required = true, content = @Content(schema = @Schema(implementation = CatalogEntity.class))) @RequestBody CatalogEntity catalogEntity) {
        CatalogEntity updatedCatalog = catalogDao.updateCatalog(id, catalogEntity);
        return updatedCatalog != null ? ResponseEntity.ok(updatedCatalog) : ResponseEntity.notFound().build();
    }

    /**
     * Deletes a catalog by its ID.
     * @param id The ID of the catalog to delete.
     * @return ResponseEntity indicating success or failure.
     */
    @EmitEvent(id = "Catalog-000001-0000000014")
    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_DELETE')")
    @DeleteMapping("/catalog/{id}")
    @Operation(summary = "Delete a catalog", description = "Deletes a catalog by its ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Catalog deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Catalog not found")
    })
    public ResponseEntity<Void> deleteCatalog(@Parameter(description = "ID of the catalog to delete") @PathVariable Long id) {
        boolean deleted = catalogDao.deleteCatalog(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

