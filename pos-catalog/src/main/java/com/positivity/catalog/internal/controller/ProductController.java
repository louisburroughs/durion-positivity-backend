package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductSearchResultDto;
import com.positivity.catalog.internal.dto.ProductStatusUpdateRequestDto;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.service.CatalogService;
import com.positivity.catalog.service.ProductLifecycleService;
import com.positivity.catalog.service.ProductMasterDataService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
@RestController
@RequestMapping("/v1/products")
@Tag(name = "Product Master API", description = "Product master data and lifecycle API")
public class ProductController {

    private final CatalogService catalogService;
    private final ProductMasterDataService productMasterDataService;
    private final ProductLifecycleService productLifecycleService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping
    @Operation(summary = "Create product")
    @ApiResponse(responseCode = "201", description = "Product created")
    @ApiResponse(responseCode = "409", description = "Duplicate SKU or manufacturer+mpn")
    @EmitEvent(id = "CATALOG_PRODUCT_CREATED", apiVersion = "1")
    public ResponseEntity<ProductDto> createProduct(@Valid @RequestBody ProductCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productMasterDataService.createProduct(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/{productId}")
    @Operation(summary = "Get product by id")
    @ApiResponse(responseCode = "200", description = "Product found")
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<ProductDto> getProduct(@PathVariable UUID productId) {
        return catalogService.getProductById(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/{productId}")
    @Operation(summary = "Update product")
    @ApiResponse(responseCode = "200", description = "Product updated")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "CATALOG_PRODUCT_UPDATED", apiVersion = "1")
    public ResponseEntity<ProductDto> updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductUpdateRequestDto request) {
        return ResponseEntity.ok(productMasterDataService.updateProduct(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping("/{productId}/status")
    @Operation(summary = "Change product operational status")
    @ApiResponse(responseCode = "200", description = "Status changed")
    @EmitEvent(id = "CATALOG_PRODUCT_STATUS_CHANGED", apiVersion = "1")
    public ResponseEntity<ProductDto> changeStatus(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductStatusUpdateRequestDto request) {
        return ResponseEntity.ok(productMasterDataService.changeProductStatus(productId, request.getStatus()));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/search")
    @Operation(summary = "Search products")
    @ApiResponse(responseCode = "200", description = "Search results")
    public ResponseEntity<ProductSearchResultDto> searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String mpn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(productMasterDataService.searchProducts(q, sku, mpn, PageRequest.of(page, size)));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @GetMapping("/{productId}/lifecycle")
    @Operation(summary = "Get product lifecycle state")
    @ApiResponse(responseCode = "200", description = "Lifecycle returned")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_GET", apiVersion = "1")
    public ResponseEntity<com.positivity.catalog.internal.dto.ProductLifecycleResponse> getLifecycleState(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getLifecycle(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW') or hasAuthority('product:lifecycle:update')")
    @GetMapping("/{productId}/replacements")
    @Operation(summary = "List replacement products")
    @ApiResponse(responseCode = "200", description = "Replacements listed")
    public ResponseEntity<List<com.positivity.catalog.internal.dto.ProductLifecycleResponse.ReplacementOption>> getReplacements(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(productLifecycleService.getReplacementProducts(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @PutMapping("/{productId}/lifecycle")
    @Operation(summary = "Set product lifecycle state")
    @ApiResponse(responseCode = "200", description = "Lifecycle updated")
    @ApiResponse(responseCode = "409", description = "Reactivation conflict")
    @EmitEvent(id = "CATALOG_PRODUCT_LIFECYCLE_CHANGED", apiVersion = "1")
    public ResponseEntity<com.positivity.catalog.internal.dto.ProductLifecycleResponse> setLifecycleState(
            @PathVariable UUID productId,
            @RequestBody com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest request) {
        return ResponseEntity.ok(productLifecycleService.setLifecycleState(
                productId,
                request.getLifecycleState(),
                request.getEffectiveAt(),
                request.getChangedBy() == null ? null : request.getChangedBy().toString(),
                request.getOverrideReason(),
                request.getEffectiveDate()));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT') or hasAuthority('product:lifecycle:update')")
    @PostMapping("/{productId}/replacements")
    @Operation(summary = "Add replacement product")
    @ApiResponse(responseCode = "201", description = "Replacement created")
    @EmitEvent(id = "CATALOG_PRODUCT_REPLACEMENT_ADD", apiVersion = "1")
    public ResponseEntity<com.positivity.catalog.internal.dto.ProductLifecycleResponse.ReplacementOption> addReplacementProduct(
            @PathVariable UUID productId,
            @RequestBody com.positivity.catalog.internal.dto.ProductReplacementRequest request) {
        Instant effectiveAt = request.getEffectiveAt() == null ? Instant.now() : request.getEffectiveAt();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productLifecycleService.addReplacementProduct(
                        productId,
                        request.getReplacementProductId(),
                        request.getPriorityOrder() == null ? 1 : request.getPriorityOrder(),
                        request.getNotes(),
                        effectiveAt));
    }
}
