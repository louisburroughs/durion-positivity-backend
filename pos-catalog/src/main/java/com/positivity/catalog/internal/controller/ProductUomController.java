package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ProductUomCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductUomDto;
import com.positivity.catalog.internal.dto.ProductUomUpdateRequestDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ProductUomService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.PRODUCT_UOM_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.PRODUCT_UOM_EDIT})
    @PostMapping
    @Operation(operationId = "addProductUom", summary = "Add Product UoM Conversion", description = """
            Adds a unit-of-measure entry to a product's conversion set, binding a UoM code to its factor \
            relative to the product's base unit; the code is normalised to upper case and the factor stored \
            at six decimal places.
            Use this tool to define how a purchase or pack unit converts for one product; do not use \
            createUomConversion, which defines global cross-unit conversions not tied to a product.
            Preconditions: the product must exist and must not already define the UoM code; a BASE-type entry \
            must carry factorToBase exactly 1.
            Required inputs: productId (UUID) path parameter plus uomCode, uomType (BASE, PURCHASE or PACK) \
            and a positive factorToBase; precisionScale is optional.
            Emits a CATALOG_PRODUCT_UOM_CREATE event and re-publishes the product fact with a bumped \
            aggregate version so downstream replicas refresh.
            Returns 404 when the product does not exist, 409 when the UoM code is already defined for the \
            product, and 400 when factorToBase is not positive or a BASE entry's factor is not 1.
            """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "UoM entry to add: the code, its role for this product, and how many"
                                    + " base units one such unit represents.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProductUomCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Case of twelve", value = """
                                                                    {"uomCode":"CS12","uomType":"PACK",
                                                                     "factorToBase":12,"precisionScale":0}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ProductUomCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productUomService.addProductUom(productId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.PRODUCT_UOM_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.PRODUCT_UOM_VIEW})
    @GetMapping
    @Operation(operationId = "listProductUoms", summary = "List Product UoM Conversions", description = """
            Returns the product's unit-of-measure conversion set ordered by UoM code, each entry carrying its \
            type, factor to base and precision scale.
            Use this tool to read a product's unit conversions; use listUomConversions instead for the \
            global, product-independent conversion table.
            Preconditions: the product must exist.
            Required inputs: productId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when the product does not exist, and 200 with an empty array when the product \
            defines no UoM entries.
            """)
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

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.PRODUCT_UOM_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.PRODUCT_UOM_EDIT})
    @PutMapping("/{uomId}")
    @Operation(operationId = "updateProductUom", summary = "Update Product UoM Conversion", description = """
            Updates the conversion factor, precision scale and optionally the type of one product UoM entry; \
            the UoM code itself is immutable.
            Use this tool to correct a factor or reclassify an entry; do not use addProductUom, which adds a \
            new code, or deleteProductUom, which removes one.
            Preconditions: the product must exist and the UoM entry must belong to it; changing the type to \
            BASE requires factorToBase exactly 1, and omitting uomType keeps the current type.
            Required inputs: productId and uomId (UUIDs) path parameters plus a positive factorToBase; \
            uomType and precisionScale are optional.
            Emits a CATALOG_PRODUCT_UOM_UPDATE event and re-publishes the product fact with a bumped \
            aggregate version so downstream replicas refresh.
            Returns 404 when the product or the UoM entry does not exist under that product, and 400 when \
            factorToBase is not positive or a BASE entry's factor is not 1.
            """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement factor and precision for the entry; uomType is optional"
                                    + " and keeps the current role when omitted.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProductUomUpdateRequestDto.class),
                                            examples = @ExampleObject(name = "Correct pack factor", value = """
                                                                    {"uomType":"PACK","factorToBase":24,"precisionScale":0}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ProductUomUpdateRequestDto request) {
        return ResponseEntity.ok(productUomService.updateProductUom(productId, uomId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.PRODUCT_UOM_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.PRODUCT_UOM_EDIT})
    @DeleteMapping("/{uomId}")
    @Operation(operationId = "deleteProductUom", summary = "Delete Product UoM Conversion", description = """
            Removes one unit-of-measure entry from a product's conversion set permanently; there is no soft \
            delete.
            Use this tool when a purchase or pack unit no longer applies to the product; do not use \
            updateProductUom, which changes an entry's factor or type while keeping it.
            Preconditions: the product must exist and the UoM entry must belong to it; no guard prevents \
            deleting the BASE entry, so callers must not orphan the conversion set.
            Required inputs: productId and uomId (UUIDs) as path parameters; there is no request body.
            Emits a CATALOG_PRODUCT_UOM_DELETE event and re-publishes the product fact with a bumped \
            aggregate version so downstream replicas refresh.
            Returns 204 on success, and 404 when the product or the UoM entry does not exist under that \
            product.
            """)
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
