package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.CreateMsrpRequestDto;
import com.positivity.catalog.internal.dto.ProductMsrpDto;
import com.positivity.catalog.internal.dto.UpdateMsrpRequestDto;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.service.ProductMsrpService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/v1/products/{productId}/msrp")
@Tag(name = "Product MSRP API", description = "Manage MSRP values with effective dates")
public class ProductMsrpController {

    private final Clock clock;
    private final ProductMsrpService productMsrpService;

    @PreAuthorize("hasAuthority('catalog:msrp:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:write"})
    @PostMapping
    @Operation(summary = "Create MSRP", description = "Creates a product MSRP record with effective date constraints.")
    @ApiResponse(
            responseCode = "201",
            description = "MSRP created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "409", description = "Temporal overlap conflict")
    @EmitEvent(id = "CATALOG_MSRP_CREATE", apiVersion = "1")
    public ResponseEntity<ProductMsrpDto> createMsrp(
            @Parameter(required = true) @PathVariable UUID productId,
            @Valid @RequestBody CreateMsrpRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productMsrpService.createMsrp(productId, request));
    }

    @PreAuthorize("hasAuthority('catalog:msrp:write')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:write"})
    @PutMapping("/{msrpId}")
    @Operation(summary = "Update MSRP", description = "Updates a non-historical MSRP record.")
    @ApiResponse(
            responseCode = "200",
            description = "MSRP updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "404", description = "MSRP record not found")
    @ApiResponse(responseCode = "409", description = "Temporal overlap or optimistic locking conflict")
    @EmitEvent(id = "CATALOG_MSRP_UPDATE", apiVersion = "1")
    public ResponseEntity<ProductMsrpDto> updateMsrp(
            @Parameter(required = true) @PathVariable UUID productId,
            @Parameter(required = true) @PathVariable UUID msrpId,
            @Valid @RequestBody UpdateMsrpRequestDto request) {
        return ResponseEntity.ok(productMsrpService.updateMsrp(productId, msrpId, request));
    }

    @PreAuthorize("hasAuthority('catalog:msrp:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:read"})
    @GetMapping("/active")
    @Operation(summary = "Get active MSRP", description = "Returns MSRP active for the provided asOf date (or today).")
    @ApiResponse(
            responseCode = "200",
            description = "Active MSRP returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    @ApiResponse(responseCode = "404", description = "No active MSRP for date")
    public ResponseEntity<ProductMsrpDto> getActiveMsrp(
            @Parameter(required = true) @PathVariable UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate targetDate = asOf == null ? LocalDate.now(clock) : asOf;
        return productMsrpService
                .getActiveMsrp(productId, targetDate)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new CatalogNotFoundException("No active MSRP found for product and date."));
    }

    @PreAuthorize("hasAuthority('catalog:msrp:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"catalog:msrp:read"})
    @GetMapping
    @Operation(summary = "List MSRP history", description = "Returns all MSRP records for a product.")
    @ApiResponse(
            responseCode = "200",
            description = "MSRP records returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductMsrpDto.class)))
    public ResponseEntity<List<ProductMsrpDto>> listMsrp(@Parameter(required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(productMsrpService.getAllMsrp(productId));
    }
}
