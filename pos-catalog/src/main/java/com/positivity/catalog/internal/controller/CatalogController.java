package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CatalogDto;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.dto.EffectiveLocationPriceResponseDto;
import com.positivity.catalog.internal.dto.GuardrailPolicyUpsertRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideCreateRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideDecisionRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideResponseDto;
import com.positivity.catalog.internal.dto.NonInventoryProductDto;
import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostResponseDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import com.positivity.catalog.service.CatalogService;
import com.positivity.catalog.service.LocationPriceOverrideService;
import com.positivity.catalog.service.ProductDetailService;
import com.positivity.catalog.service.ProductLifecycleService;
import com.positivity.catalog.service.SupplierItemCostService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/v1/products")
@Tag(name = "Catalog API", description = "API for managing catalog items")
public class CatalogController {

    private final CatalogService catalogService;
    private final ProductDetailService productDetailService;
    private final ProductLifecycleService productLifecycleService;
    private final LocationPriceOverrideService locationPriceOverrideService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/pricing/guardrail-policies")
    @Operation(summary = "Upsert location guardrail policy", description = "Creates or updates the active LOCATION guardrail policy used by price overrides.")
    @ApiResponse(responseCode = "200", description = "Guardrail policy upserted", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid policy payload")
    @EmitEvent(id = "CATALOG_GUARDRAIL_POLICY_UPSERT", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> upsertLocationGuardrailPolicy(
            @RequestBody GuardrailPolicyUpsertRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.upsertLocationGuardrailPolicy(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/pricing/location-overrides")
    @Operation(summary = "Create location price override", description = "Creates a location-specific price override and enforces guardrails for margin and discount limits.")
    @ApiResponse(responseCode = "201", description = "Override created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Guardrail validation failed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "CATALOG_PRICE_OVERRIDE_CREATE", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> createLocationPriceOverride(
            @RequestBody LocationPriceOverrideCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationPriceOverrideService.createOverride(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/pricing/effective-price/{locationId}/{productId}")
    @Operation(summary = "Get effective location price", description = "Resolves effective price using precedence: ACTIVE override first, otherwise base price.")
    @ApiResponse(responseCode = "200", description = "Effective price returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EffectiveLocationPriceResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "No pricing context found")
    public ResponseEntity<EffectiveLocationPriceResponseDto> getEffectiveLocationPrice(
            @Parameter(description = "Location ID", required = true) @PathVariable UUID locationId,
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(locationPriceOverrideService.getEffectivePrice(locationId, productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('pricing:override:approve')")
    @PostMapping("/pricing/location-overrides/{overrideId}/approve")
    @Operation(summary = "Approve pending location price override", description = "Approves a pending override and activates it as the effective location price.")
    @ApiResponse(responseCode = "200", description = "Override approved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid approval request")
    @ApiResponse(responseCode = "404", description = "Override or approval request not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @EmitEvent(id = "CATALOG_PRICE_OVERRIDE_APPROVED", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> approveLocationPriceOverride(
            @Parameter(description = "Override ID", required = true) @PathVariable UUID overrideId,
            @RequestBody LocationPriceOverrideDecisionRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.approveOverride(overrideId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('pricing:override:approve')")
    @PostMapping("/pricing/location-overrides/{overrideId}/reject")
    @Operation(summary = "Reject pending location price override", description = "Rejects a pending override, persists rejection metadata, and marks the request as terminal.")
    @ApiResponse(responseCode = "200", description = "Override rejected", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid rejection request")
    @ApiResponse(responseCode = "404", description = "Override or approval request not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @EmitEvent(id = "CATALOG_PRICE_OVERRIDE_REJECTED", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> rejectLocationPriceOverride(
            @Parameter(description = "Override ID", required = true) @PathVariable UUID overrideId,
            @RequestBody LocationPriceOverrideDecisionRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.rejectOverride(overrideId, request));
    }
    private final SupplierItemCostService supplierItemCostService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/{productId}")
    @Operation(summary = "Get a product by ID", description = "Retrieves a specific product by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved product", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<ProductDto> getProductById(
            @Parameter(description = "ID of the product to be obtained") @PathVariable UUID productId) {
        return catalogService.getProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/costs/supplier-item")
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
    @GetMapping("/costs/supplier-item/{supplierId}/{itemId}")
    @Operation(summary = "Get supplier-item cost tiers", description = "Gets the supplier-item cost structure and ordered quantity tiers.")
    @ApiResponse(responseCode = "200", description = "Supplier-item cost found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SupplierItemCostResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Supplier-item cost not found")
    public ResponseEntity<SupplierItemCostResponseDto> getSupplierItemCost(
            @Parameter(description = "Supplier ID", required = true) @PathVariable UUID supplierId,
            @Parameter(description = "Item ID", required = true) @PathVariable UUID itemId) {
        return ResponseEntity.ok(supplierItemCostService.getSupplierItemCost(supplierId, itemId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/costs/supplier-item/{supplierId}/{itemId}")
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
    @DeleteMapping("/costs/supplier-item/{supplierId}/{itemId}")
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

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @GetMapping("/{productId}/lifecycle")
    @Operation(summary = "Get product lifecycle state", description = "Retrieves lifecycle state and replacement suggestions for a product.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved lifecycle state", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductLifecycleResponse.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_GET", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse> getProductLifecycle(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getLifecycle(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @PutMapping("/{productId}/lifecycle")
    @Operation(summary = "Set product lifecycle state", description = "Sets lifecycle state to ACTIVE, INACTIVE, or DISCONTINUED with effective date semantics.")
    @ApiResponse(responseCode = "200", description = "Lifecycle state updated successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductLifecycleResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Missing override permission")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse> updateProductLifecycle(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @RequestBody ProductLifecycleUpdateRequest request) {
        return ResponseEntity.ok(productLifecycleService.updateLifecycle(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @PostMapping("/{productId}/replacements")
    @Operation(summary = "Add replacement product", description = "Adds a replacement suggestion to a discontinued product.")
    @ApiResponse(responseCode = "201", description = "Replacement added successfully")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_REPLACEMENT_ADD", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse.ReplacementOption> addReplacementProduct(
            @Parameter(description = "ID of discontinued product", required = true) @PathVariable UUID productId,
            @RequestBody ProductReplacementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productLifecycleService.addReplacement(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/name/{name}")
    @Operation(summary = "Get products by name", description = "Retrieves a list of products matching the given name.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved products", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    public List<ProductDto> getProductByName(
            @Parameter(description = "Name of the products to be obtained") @PathVariable String name) {
        return catalogService.getProductsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("product/{productId}")
    @Operation(summary = "Get product details with pricing and availability", description = "Retrieves a consolidated view of product information including catalog data, location-specific pricing, and availability. Implements graceful degradation and returns partial data when non-critical services are unavailable.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved product details (may be partial if some services unavailable)", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDetailView.class)))
    @ApiResponse(responseCode = "400", description = "Invalid location ID")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "500", description = "Unexpected server error while retrieving product details")
    public ResponseEntity<ProductDetailView> getProductDetailView(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @Parameter(description = "Location/store ID for location-specific data", required = true) @RequestParam(name = "location_id") UUID locationId) {

        log.info("Product detail view requested: productId={}, locationId={}", productId, locationId);
        if (locationId == null) {
            log.warn("Invalid location_id provided: {}", locationId);
            return ResponseEntity.badRequest().build();
        }

        ProductDetailView productDetail = productDetailService.getProductDetail(productId, locationId);
        if (productDetail == null) {
            log.warn("Product not found: productId={}", productId);
            return ResponseEntity.notFound().build();
        }

        log.debug("Product detail view generated with confidence={}", productDetail.getConfidence());
        return ResponseEntity.ok(productDetail);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/service/{serviceId}")
    @Operation(summary = "Get a service by ID", description = "Retrieves a specific service by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved service", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    @ApiResponse(responseCode = "404", description = "Service not found")
    public ResponseEntity<ServiceDto> getServiceById(
            @Parameter(description = "ID of the service to be obtained") @PathVariable UUID serviceId) {
        return catalogService.getServiceById(serviceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/service/name/{name}")
    @Operation(summary = "Get services by name", description = "Retrieves a list of services matching the given name.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved services", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    public List<ServiceDto> getServiceByName(
            @Parameter(description = "Name of the services to be obtained") @PathVariable String name) {
        return catalogService.getServicesByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/noninventory/{productId}")
    @Operation(summary = "Get a non-inventory product by ID", description = "Retrieves a specific non-inventory product by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved non-inventory product", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NonInventoryProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Non-inventory product not found")
    public ResponseEntity<NonInventoryProductDto> getNonInventoryProductById(
            @Parameter(description = "ID of the non-inventory product to be obtained") @PathVariable UUID productId) {
        return catalogService.getNonInventoryProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/noninventory/name/{name}")
    @Operation(summary = "Get non-inventory products by name", description = "Retrieves a list of non-inventory products matching the given name.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved non-inventory products", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NonInventoryProductDto.class)))
    public List<NonInventoryProductDto> getNonInventoryProductByName(
            @Parameter(description = "Name of the non-inventory products to be obtained") @PathVariable String name) {
        return catalogService.getNonInventoryProductsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/catalog/{catalogId}")
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
    @GetMapping("/catalog/name/{name}")
    @Operation(summary = "Get catalogs by name", description = "Retrieves a list of catalogs matching the given name.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved catalogs", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    public List<CatalogDto> getCatalogByName(
            @Parameter(description = "Name of the catalogs to be obtained") @PathVariable String name) {
        return catalogService.getCatalogsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/{type}")
    @Operation(summary = "Add a new catalog item", description = "Adds a new product, service, or non-inventory product to the catalog.")
    @ApiResponse(responseCode = "201", description = "Catalog item created successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogItemResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid item type or request body")
    @EmitEvent(id = "CATALOG_ITEM_CREATE", apiVersion = "1")
    public ResponseEntity<CatalogItemResponseDto> addCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @RequestBody CatalogItemRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCatalogItem(type, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/{type}/{catalogId}")
    @Operation(summary = "Update an existing catalog item", description = "Updates an existing product, service, or non-inventory product in the catalog.")
    @ApiResponse(responseCode = "200", description = "Catalog item updated successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogItemResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid item type or request body")
    @ApiResponse(responseCode = "404", description = "Catalog item not found")
    @EmitEvent(id = "CATALOG_ITEM_UPDATE", apiVersion = "1")
    public ResponseEntity<CatalogItemResponseDto> updateCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @Parameter(description = "ID of the catalog item to update") @PathVariable UUID catalogId,
            @RequestBody CatalogItemRequestDto request) {
        return catalogService.updateCatalogItem(type, catalogId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_DELETE')")
    @DeleteMapping("/{type}/{catalogId}")
    @Operation(summary = "Delete a catalog item", description = "Deletes a product, service, or non-inventory product from the catalog by its ID.")
    @ApiResponse(responseCode = "204", description = "Catalog item deleted successfully")
    @ApiResponse(responseCode = "400", description = "Invalid item type")
    @ApiResponse(responseCode = "404", description = "Catalog item not found")
    public ResponseEntity<Void> deleteCatalogItem(
            @Parameter(description = "Type of catalog item (product, service, noninventory)") @PathVariable String type,
            @Parameter(description = "ID of the catalog item to delete") @PathVariable UUID catalogId) {
        return catalogService.deleteCatalogItem(type, catalogId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/catalog")
    @Operation(summary = "Add a new catalog", description = "Adds a new catalog.")
    @ApiResponse(responseCode = "201", description = "Catalog created successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @EmitEvent(id = "CATALOG_CATALOG_CREATE", apiVersion = "1")
    public ResponseEntity<CatalogDto> addCatalog(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Catalog object to be added", required = true, content = @Content(schema = @Schema(implementation = CatalogDto.class))) @RequestBody CatalogDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCatalog(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/catalog/{catalogId}")
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
    @DeleteMapping("/catalog/{catalogId}")
    @Operation(summary = "Delete a catalog", description = "Deletes a catalog by its ID.")
    @ApiResponse(responseCode = "204", description = "Catalog deleted successfully")
    @ApiResponse(responseCode = "404", description = "Catalog not found")
    public ResponseEntity<Void> deleteCatalog(
            @Parameter(description = "ID of the catalog to delete") @PathVariable UUID catalogId) {
        return catalogService.deleteCatalog(catalogId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/substitutes/{productId}")
    @Operation(summary = "Get substitute parts", description = "Returns list of substitute parts for a given productId.")
    @ApiResponse(responseCode = "501", description = "Not implemented")
    public ResponseEntity<List<ProductDto>> getPartSubstitutes(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        log.warn("Substitutes endpoint not implemented yet: productId={}", productId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
