package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.service.VendorDirectoryService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the AP vendor directory (Issue #816).
 *
 * <p>
 * Provides vendor name → vendorId resolution for the frontend vendor-payment
 * typeahead, mirroring the CRM party search pattern used by customer-lookup:
 * a debounce-friendly name search plus a single-vendor fetch to label
 * deep-linked ids.
 *
 * @see VendorDirectoryService
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/vendors")
@RequiredArgsConstructor
@Tag(
        name = "Vendor Directory API",
        description = "Vendor search and lookup endpoints for name-to-vendorId resolution (typeahead support)")
@Validated
public class VendorDirectoryController {

    private final VendorDirectoryService vendorDirectoryService;

    /**
     * Search vendors by name (typeahead).
     *
     * GET /v1/accounting/vendors?name={term}
     *
     * @param name  optional name search term; blank/absent lists all vendors
     * @param limit maximum results to return (server caps at 100)
     * @return name-matched vendors ordered by name
     */
    @GetMapping
    @EmitEvent(id = "ACCOUNTING_VENDOR_SEARCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(
            summary = "Search vendors by name",
            description = "Case-insensitive name-contains search over the AP vendor directory, ordered by name."
                    + " Omit the name parameter to list all vendors (up to the limit).",
            tags = {"Vendor Directory API"})
    @ApiResponse(
            responseCode = "200",
            description = "Matching vendors returned",
            content = @Content(schema = @Schema(implementation = VendorResponse.class)))
    public ResponseEntity<List<VendorResponse>> searchVendors(
            @Parameter(description = "Name search term (case-insensitive contains)", example = "acme")
                    @Nullable
                    @RequestParam(required = false)
                    String name,
            @Parameter(description = "Maximum results to return (server caps at 100)", example = "20")
                    @RequestParam(required = false, defaultValue = "20")
                    int limit) {
        log.info("Received vendor search request | termLength={} | limit={}", name == null ? 0 : name.length(), limit);

        return ResponseEntity.ok(vendorDirectoryService.searchVendors(name, limit));
    }

    /**
     * Get a single vendor by id (label resolution for preset ids).
     *
     * GET /v1/accounting/vendors/{vendorId}
     *
     * @param vendorId the vendor UUID
     * @return vendor with 200 status, or 404 if not found
     */
    @GetMapping("/{vendorId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(
            summary = "Get vendor by id",
            description = "Resolves a single vendor by its identifier, e.g. to display a name for a deep-linked id",
            tags = {"Vendor Directory API"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor found",
            content = @Content(schema = @Schema(implementation = VendorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Vendor not found")
    public ResponseEntity<VendorResponse> getVendorById(
            @Parameter(description = "Vendor identifier", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID vendorId) {
        log.info("Received vendor lookup request | vendorId={}", vendorId);

        return vendorDirectoryService
                .getVendorById(vendorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
