package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CatalogSearchResultDto;
import com.positivity.catalog.internal.dto.EffectiveLocationPriceResponseDto;
import com.positivity.catalog.internal.dto.GuardrailPolicyUpsertRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideCreateRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideDecisionRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideResponseDto;
import com.positivity.catalog.internal.dto.NonInventoryProductDto;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductDetailView;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.service.CatalogService;
import com.positivity.catalog.service.LocationPriceOverrideService;
import com.positivity.catalog.service.ProductDetailService;
import com.positivity.catalog.service.ProductLifecycleService;
import com.positivity.catalog.service.ProductMasterDataService;
import com.positivity.catalog.service.ProductSearchService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@Tag(name = "Products API", description = "API for products, lifecycle, and pricing")
public class ProductController {

    private final CatalogService catalogService;
    private final ProductDetailService productDetailService;
    private final ProductLifecycleService productLifecycleService;
    private final LocationPriceOverrideService locationPriceOverrideService;
    private final ProductMasterDataService productMasterDataService;
    private final ProductSearchService productSearchService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping("/pricing/guardrail-policies")
    @Operation(
            summary = "Upsert location guardrail policy",
            description = "Creates or updates the active LOCATION guardrail policy used by price overrides.")
    @ApiResponse(
            responseCode = "200",
            description = "Guardrail policy upserted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid policy payload")
    @EmitEvent(id = "CATALOG_GUARDRAIL_POLICY_UPSERT", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> upsertLocationGuardrailPolicy(
            @Valid @RequestBody GuardrailPolicyUpsertRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.upsertLocationGuardrailPolicy(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping("/pricing/location-overrides")
    @Operation(
            summary = "Create location price override",
            description =
                    "Creates a location-specific price override and enforces guardrails for margin and discount limits.")
    @ApiResponse(
            responseCode = "201",
            description = "Override created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Guardrail validation failed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "CATALOG_LOCATION_OVERRIDE_CREATE", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> createLocationPriceOverride(
            @Valid @RequestBody LocationPriceOverrideCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationPriceOverrideService.createOverride(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/pricing/effective-price/{locationId}/{productId}")
    @Operation(
            summary = "Get effective location price",
            description = "Resolves effective price using precedence: ACTIVE override first, otherwise base price.")
    @ApiResponse(
            responseCode = "200",
            description = "Effective price returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EffectiveLocationPriceResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "No pricing context found")
    public ResponseEntity<EffectiveLocationPriceResponseDto> getEffectiveLocationPrice(
            @Parameter(description = "Location ID", required = true) @PathVariable UUID locationId,
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(locationPriceOverrideService.getEffectivePrice(locationId, productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('pricing:override:approve')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "pricing:override:approve"})
    @PostMapping("/pricing/location-overrides/{overrideId}/approve")
    @Operation(
            summary = "Approve pending location price override",
            description = "Approves a pending override and activates it as the effective location price.")
    @ApiResponse(
            responseCode = "200",
            description = "Override approved",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid approval request")
    @ApiResponse(responseCode = "404", description = "Override or approval request not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @EmitEvent(id = "CATALOG_LOCATION_OVERRIDE_APPROVE", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> approveLocationPriceOverride(
            @Parameter(description = "Override ID", required = true) @PathVariable UUID overrideId,
            @Valid @RequestBody LocationPriceOverrideDecisionRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.approveOverride(overrideId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('pricing:override:approve')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "pricing:override:approve"})
    @PostMapping("/pricing/location-overrides/{overrideId}/reject")
    @Operation(
            summary = "Reject pending location price override",
            description = "Rejects a pending override, persists rejection metadata, and marks the request as terminal.")
    @ApiResponse(
            responseCode = "200",
            description = "Override rejected",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationPriceOverrideResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid rejection request")
    @ApiResponse(responseCode = "404", description = "Override or approval request not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @EmitEvent(id = "CATALOG_LOCATION_OVERRIDE_REJECT", apiVersion = "1")
    public ResponseEntity<LocationPriceOverrideResponseDto> rejectLocationPriceOverride(
            @Parameter(description = "Override ID", required = true) @PathVariable UUID overrideId,
            @Valid @RequestBody LocationPriceOverrideDecisionRequestDto request) {
        return ResponseEntity.ok(locationPriceOverrideService.rejectOverride(overrideId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/search")
    @Operation(
            summary = "Search catalog products",
            description =
                    "Cursor-based product search with optional free-text query and exact filters for brand, category, and SKU. "
                            + "Pass detailed=true to enrich each row inline with lifecycle state + effective instant and the "
                            + "product's active MSRP (amount, currency, effective window), resolved server-side in a single "
                            + "request. Products without an active MSRP return null price fields.")
    @ApiResponse(
            responseCode = "200",
            description = "Search results",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CatalogSearchResultDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameter (e.g., non-numeric limit)")
    public ResponseEntity<CatalogSearchResultDto> searchProducts(
            @Parameter(description = "Free-text search query (matches product name and description)")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Filter by manufacturer brand (exact, case-insensitive)")
                    @RequestParam(required = false)
                    String brand,
            @Parameter(description = "Filter by category name (exact, case-insensitive)")
                    @RequestParam(required = false)
                    String category,
            @Parameter(description = "Filter by SKU (exact match, case-insensitive)") @RequestParam(required = false)
                    String sku,
            @Parameter(description = "Pagination cursor from previous response") @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Maximum number of results (1–100)") @RequestParam(defaultValue = "20") int limit,
            @Parameter(
                            description =
                                    "When true, enrich each row with lifecycle state, effective instant, and active MSRP")
                    @RequestParam(defaultValue = "false")
                    boolean detailed) {
        return ResponseEntity.ok(productSearchService.searchProducts(q, brand, category, sku, cursor, limit, detailed));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @PostMapping
    @Operation(
            summary = "Create product master record",
            description = "Creates a product master record with immutable SKU and uniqueness checks.")
    @ApiResponse(
            responseCode = "201",
            description = "Product created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Business conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_CREATED", apiVersion = "1")
    public ResponseEntity<ProductDto> createProduct(@Valid @RequestBody ProductCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productMasterDataService.createProduct(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PutMapping("/{productId}")
    @Operation(
            summary = "Update product master record",
            description = "Updates mutable product master fields. SKU is immutable.")
    @ApiResponse(
            responseCode = "200",
            description = "Product updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Business conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_UPDATED", apiVersion = "1")
    public ResponseEntity<ProductDto> updateProduct(
            @Parameter(description = "ID of the product to update", required = true) @PathVariable UUID productId,
            @Valid @RequestBody ProductUpdateRequestDto request) {
        return ResponseEntity.ok(productMasterDataService.updateProduct(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{productId}")
    @Operation(summary = "Get a product by ID", description = "Retrieves a specific product by its unique ID.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved product",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<ProductDto> getProductById(
            @Parameter(description = "ID of the product to be obtained") @PathVariable UUID productId) {
        return catalogService
                .getProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/name/{name}")
    @Operation(summary = "Get products by name", description = "Retrieves a list of products matching the given name.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved products",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    public List<ProductDto> getProductByName(
            @Parameter(description = "Name of the products to be obtained") @PathVariable String name) {
        return catalogService.getProductsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{productId}/detail")
    @Operation(
            summary = "Get product details with pricing and availability",
            description =
                    "Retrieves a consolidated view of product information including catalog data, location-specific pricing, and availability. Implements graceful degradation and returns partial data when non-critical services are unavailable.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved product details (may be partial if some services unavailable)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductDetailView.class)))
    @ApiResponse(responseCode = "400", description = "Invalid location ID")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "500", description = "Unexpected server error while retrieving product details")
    public ResponseEntity<ProductDetailView> getProductDetailView(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @Parameter(description = "Location/store ID for location-specific data", required = true)
                    @RequestParam(name = "location_id")
                    UUID locationId) {

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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/services/search")
    @Operation(
            summary = "Search catalog services",
            description = "Free-text substring search over service names for typeahead selection.")
    @ApiResponse(
            responseCode = "200",
            description = "Matching services",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ServiceDto.class))))
    public ResponseEntity<List<ServiceDto>> searchServices(
            @Parameter(description = "Free-text query matching service name (case-insensitive substring)")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Maximum number of results (1–100)") @RequestParam(defaultValue = "20")
                    int limit) {
        return ResponseEntity.ok(catalogService.searchServices(q, limit));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/services/{serviceId}")
    @Operation(summary = "Get a service by ID", description = "Retrieves a specific service by its unique ID.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved service",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    @ApiResponse(responseCode = "404", description = "Service not found")
    public ResponseEntity<ServiceDto> getServiceById(
            @Parameter(description = "ID of the service to be obtained") @PathVariable UUID serviceId) {
        return catalogService
                .getServiceById(serviceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/services/name/{name}")
    @Operation(summary = "Get services by name", description = "Retrieves a list of services matching the given name.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved services",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    public List<ServiceDto> getServiceByName(
            @Parameter(description = "Name of the services to be obtained") @PathVariable String name) {
        return catalogService.getServicesByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/noninventory/{productId}")
    @Operation(
            summary = "Get a non-inventory product by ID",
            description = "Retrieves a specific non-inventory product by its unique ID.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved non-inventory product",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NonInventoryProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Non-inventory product not found")
    public ResponseEntity<NonInventoryProductDto> getNonInventoryProductById(
            @Parameter(description = "ID of the non-inventory product to be obtained") @PathVariable UUID productId) {
        return catalogService
                .getNonInventoryProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/noninventory/name/{name}")
    @Operation(
            summary = "Get non-inventory products by name",
            description = "Retrieves a list of non-inventory products matching the given name.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved non-inventory products",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NonInventoryProductDto.class)))
    public List<NonInventoryProductDto> getNonInventoryProductByName(
            @Parameter(description = "Name of the non-inventory products to be obtained") @PathVariable String name) {
        return catalogService.getNonInventoryProductsByName(name);
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW", "product:lifecycle:update"})
    @GetMapping("/{productId}/lifecycle")
    @Operation(
            summary = "Get product lifecycle state",
            description = "Retrieves lifecycle state and replacement suggestions for a product.")
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved lifecycle state",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductLifecycleResponse.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_GET", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse> getProductLifecycle(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getLifecycle(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW", "product:lifecycle:update"})
    @GetMapping("/{productId}/replacements")
    @Operation(summary = "List replacement products", description = "Returns replacement options for a product.")
    @ApiResponse(responseCode = "200", description = "Replacements listed")
    public ResponseEntity<List<ProductLifecycleResponse.ReplacementOption>> getReplacements(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getReplacementProducts(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT", "product:lifecycle:update"})
    @PutMapping("/{productId}/lifecycle")
    @Operation(
            summary = "Set product lifecycle state",
            description = "Sets lifecycle state to ACTIVE, INACTIVE, or DISCONTINUED with effective date semantics.")
    @ApiResponse(
            responseCode = "200",
            description = "Lifecycle state updated successfully",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ProductLifecycleResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Missing override permission")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Lifecycle business rule conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductLifecycleResponse> setLifecycleState(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId,
            @RequestBody ProductLifecycleUpdateRequest request) {
        return ResponseEntity.ok(productLifecycleService.updateLifecycle(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT", "product:lifecycle:update"})
    @PostMapping("/{productId}/replacements")
    @Operation(
            summary = "Add replacement product",
            description = "Adds a replacement suggestion to a discontinued product.")
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{productId}/substitutes")
    @Operation(
            summary = "Get substitute parts",
            description = "Returns list of substitute parts for a given productId.")
    @ApiResponse(
            responseCode = "200",
            description = "Substitute parts returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductDto.class)))
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<List<ProductDto>> getPartSubstitutes(
            @Parameter(description = "ID of the product", required = true) @PathVariable UUID productId) {
        List<ProductDto> substitutes = productLifecycleService.getReplacementProducts(productId).stream()
                .map(ProductLifecycleResponse.ReplacementOption::getReplacementProductId)
                .filter(Objects::nonNull)
                .distinct()
                .map(catalogService::getProductById)
                .flatMap(java.util.Optional::stream)
                .toList();
        return ResponseEntity.ok(substitutes);
    }
}
