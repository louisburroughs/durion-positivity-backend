package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.UomConversionCreateRequestDto;
import com.positivity.catalog.internal.dto.UomConversionDto;
import com.positivity.catalog.internal.dto.UomConversionUpdateRequestDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.UomConversionService;
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
@RequestMapping("/v1/products/uom-conversions")
@Tag(name = "UOM Conversion API", description = "Unit of measure conversion API")
public class UomConversionController {

    private final UomConversionService uomConversionService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.UOM_CONVERSION_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.UOM_CONVERSION_EDIT})
    @PostMapping
    @Operation(operationId = "createUomConversion", summary = "Create UoM Conversion", description = """
            Creates a global, directional unit-of-measure conversion stating how many target units one \
            source unit represents; codes are normalised to upper case and the factor stored at six decimal \
            places.
            Use this tool for conversions that apply across the whole catalog; do not use addProductUom, \
            which defines a conversion for a single product's own unit set.
            Preconditions: no active conversion may already exist for the same from and to code pair; a \
            self-conversion (same code both sides) must carry factor 1.
            Required inputs: fromUomCode, toUomCode and a positive conversionFactor; createdBy is optional.
            Emits a CATALOG_UOM_CONVERSION_CREATE event; the reverse direction is derived at read time, not \
            stored.
            Returns 409 when an active conversion already exists for the pair, and 400 when either code is \
            blank, the factor is not positive, or a self-conversion factor is not 1.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Conversion created",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = UomConversionDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "CATALOG_UOM_CONVERSION_CREATE", apiVersion = "1")
    public ResponseEntity<UomConversionDto> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Directional conversion pair with the factor of target units per one"
                                    + " source unit.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = UomConversionCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Case to each", value = """
                                                                    {"fromUomCode":"CS","toUomCode":"EA",
                                                                     "conversionFactor":12,"createdBy":"catalog-admin"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UomConversionCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uomConversionService.createConversion(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.UOM_CONVERSION_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.UOM_CONVERSION_VIEW})
    @GetMapping
    @Operation(operationId = "listUomConversions", summary = "List Active UoM Conversions", description = """
            Returns every active unit-of-measure conversion in the global table; deactivated conversions are \
            excluded.
            Use this tool to survey available conversions or find a conversion id; use getUomConversionById \
            instead when the id is already known, and listProductUoms for a product's own unit set.
            Preconditions: none; the list is unfiltered and unpaged.
            Required inputs: none, and there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when no active conversions exist, so an empty result is not an \
            error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Conversions listed",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UomConversionDto.class))))
    public ResponseEntity<List<UomConversionDto>> list() {
        return ResponseEntity.ok(uomConversionService.listActiveConversions());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.UOM_CONVERSION_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.UOM_CONVERSION_VIEW})
    @GetMapping("/{id}")
    @Operation(operationId = "getUomConversionById", summary = "Get UoM Conversion by ID", description = """
            Returns one unit-of-measure conversion with its from and to codes, factor and active flag; \
            inactive conversions are returned here even though listUomConversions hides them.
            Use this tool when the conversion id is already known; use listUomConversions instead to browse \
            the active table.
            Preconditions: the conversion must exist.
            Required inputs: id (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no conversion exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Conversion found",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = UomConversionDto.class)))
    @ApiResponse(responseCode = "404", description = "Conversion not found")
    public ResponseEntity<UomConversionDto> get(@Parameter(description = "Conversion ID") @PathVariable UUID id) {
        return ResponseEntity.ok(uomConversionService.getConversion(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.UOM_CONVERSION_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.UOM_CONVERSION_EDIT})
    @PutMapping("/{id}")
    @Operation(operationId = "updateUomConversion", summary = "Update UoM Conversion Factor", description = """
            Updates the factor of an existing unit-of-measure conversion; the from and to codes are immutable, \
            so redefining a pair means deactivating this conversion and creating a new one.
            Use this tool to correct a factor; do not use createUomConversion, which adds a new pair, or \
            deactivateUomConversion, which retires one.
            Preconditions: the conversion must exist; a self-conversion must keep factor 1.
            Required inputs: id (UUID) path parameter and a positive conversionFactor in the body, stored at \
            six decimal places.
            Emits a CATALOG_UOM_CONVERSION_UPDATE event; derived inverse factors reflect the change \
            immediately.
            Returns 404 when no conversion exists for the supplied id, and 400 when the factor is not \
            positive or a self-conversion factor is not 1.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Conversion updated",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = UomConversionDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Conversion not found")
    @EmitEvent(id = "CATALOG_UOM_CONVERSION_UPDATE", apiVersion = "1")
    public ResponseEntity<UomConversionDto> update(
            @Parameter(description = "Conversion ID") @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The corrected conversion factor for the existing from/to pair.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = UomConversionUpdateRequestDto.class),
                                            examples = @ExampleObject(name = "Correct factor", value = """
                                                                    {"conversionFactor":24}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UomConversionUpdateRequestDto request) {
        return ResponseEntity.ok(uomConversionService.updateConversion(id, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.UOM_CONVERSION_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.UOM_CONVERSION_EDIT})
    @DeleteMapping("/{id}")
    @Operation(operationId = "deactivateUomConversion", summary = "Deactivate UoM Conversion", description = """
            Marks a unit-of-measure conversion inactive so it disappears from listUomConversions and from \
            factor lookups; the row is kept and remains readable by id.
            Use this tool to retire a conversion pair; do not use updateUomConversion, which changes the \
            factor while keeping it active — there is no reactivation endpoint, so recreate the pair with \
            createUomConversion if it is needed again.
            Preconditions: the conversion must exist; deactivating an already inactive conversion succeeds \
            idempotently.
            Required inputs: id (UUID) as a path parameter; there is no request body.
            Emits a CATALOG_UOM_CONVERSION_DEACTIVATE event; product-level UoM sets are unaffected.
            Returns 204 on success, and 404 when no conversion exists for the supplied id.
            """)
    @ApiResponse(responseCode = "204", description = "Conversion deactivated")
    @ApiResponse(responseCode = "404", description = "Conversion not found")
    @EmitEvent(id = "CATALOG_UOM_CONVERSION_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<Void> deactivate(@Parameter(description = "Conversion ID") @PathVariable UUID id) {
        uomConversionService.deactivateConversion(id);
        return ResponseEntity.noContent().build();
    }
}
