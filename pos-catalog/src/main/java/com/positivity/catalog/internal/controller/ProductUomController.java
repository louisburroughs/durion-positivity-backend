package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ProductUomCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductUomDto;
import com.positivity.catalog.internal.dto.ProductUomUpdateRequestDto;
import com.positivity.catalog.service.ProductUomService;
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
@RequestMapping("/v1/products/{productId}/uoms")
@Tag(
        name = "Product UoM API",
        description = "Per-product unit-of-measure conversion set (purchase/pack UoMs with factor to base UoM)")
public class ProductUomController {

    private final ProductUomService productUomService;

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping
    @Operation(
            summary = "Add product UoM conversion",
            description = "Adds a purchase/pack (or base) UoM with its conversion factor to the product's base UoM"
                    + " and a precision scale. Re-emits the product contract event.",
            operationId = "addProductUom")
    @ApiResponse(
            responseCode = "201",
            description = "Product UoM created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductUomDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "UoM code already defined for product")
    @EmitEvent(id = "CATALOG_PRODUCT_UOM_CREATE", apiVersion = "1")
    public ResponseEntity<ProductUomDto> addProductUom(
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId,
            @Valid @RequestBody ProductUomCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productUomService.addProductUom(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping
    @Operation(
            summary = "List product UoM conversions",
            description = "Returns the product's UoM conversion set ordered by UoM code.",
            operationId = "listProductUoms")
    @ApiResponse(
            responseCode = "200",
            description = "Product UoMs listed",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ProductUomDto.class))))
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<List<ProductUomDto>> listProductUoms(
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productUomService.listProductUoms(productId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PutMapping("/{uomId}")
    @Operation(
            summary = "Update product UoM conversion",
            description = "Updates factor, precision scale, and optionally the UoM role. The UoM code is immutable."
                    + " Re-emits the product contract event.",
            operationId = "updateProductUom")
    @ApiResponse(
            responseCode = "200",
            description = "Product UoM updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductUomDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Product or UoM not found")
    @EmitEvent(id = "CATALOG_PRODUCT_UOM_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductUomDto> updateProductUom(
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId,
            @Parameter(description = "Product UoM ID", required = true) @PathVariable UUID uomId,
            @Valid @RequestBody ProductUomUpdateRequestDto request) {
        return ResponseEntity.ok(productUomService.updateProductUom(productId, uomId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @DeleteMapping("/{uomId}")
    @Operation(
            summary = "Delete product UoM conversion",
            description = "Removes a UoM from the product's conversion set and re-emits the product contract event.",
            operationId = "deleteProductUom")
    @ApiResponse(responseCode = "204", description = "Product UoM deleted")
    @ApiResponse(responseCode = "404", description = "Product or UoM not found")
    @EmitEvent(id = "CATALOG_PRODUCT_UOM_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteProductUom(
            @Parameter(description = "Product ID", required = true) @PathVariable UUID productId,
            @Parameter(description = "Product UoM ID", required = true) @PathVariable UUID uomId) {
        productUomService.deleteProductUom(productId, uomId);
        return ResponseEntity.noContent().build();
    }
}
