package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.UomConversionCreateRequestDto;
import com.positivity.catalog.internal.dto.UomConversionDto;
import com.positivity.catalog.internal.dto.UomConversionUpdateRequestDto;
import com.positivity.catalog.service.UomConversionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PostMapping
    @Operation(summary = "Create UOM conversion", operationId = "createUomConversion")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Conversion created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UomConversionDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @EmitEvent(id = "CATALOG_UOM_CONVERSION_CREATE", apiVersion = "1")
    public ResponseEntity<UomConversionDto> create(@Valid @RequestBody UomConversionCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uomConversionService.createConversion(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping
    @Operation(summary = "List active conversions", operationId = "listUomConversions")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conversions listed", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = UomConversionDto.class))))
    })
    public ResponseEntity<List<UomConversionDto>> list() {
        return ResponseEntity.ok(uomConversionService.listActiveConversions());
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @GetMapping("/{id}")
    @Operation(summary = "Get conversion", operationId = "getUomConversionById")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conversion found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UomConversionDto.class))),
            @ApiResponse(responseCode = "404", description = "Conversion not found")
    })
    public ResponseEntity<UomConversionDto> get(
            @Parameter(description = "Conversion ID") @PathVariable UUID id) {
        return ResponseEntity.ok(uomConversionService.getConversion(id));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @PutMapping("/{id}")
    @Operation(summary = "Update conversion factor", operationId = "updateUomConversion")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conversion updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UomConversionDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "Conversion not found")
    })
    @EmitEvent(id = "CATALOG_UOM_CONVERSION_UPDATE", apiVersion = "1")
    public ResponseEntity<UomConversionDto> update(
            @Parameter(description = "Conversion ID") @PathVariable UUID id,
            @Valid @RequestBody UomConversionUpdateRequestDto request) {
        return ResponseEntity.ok(uomConversionService.updateConversion(id, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate conversion", operationId = "deactivateUomConversion")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Conversion deactivated"),
            @ApiResponse(responseCode = "404", description = "Conversion not found")
    })
    @EmitEvent(id = "CATALOG_UOM_CONVERSION_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "Conversion ID") @PathVariable UUID id) {
        uomConversionService.deactivateConversion(id);
        return ResponseEntity.noContent().build();
    }
}
