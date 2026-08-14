package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SupplierPriceEntryDto;
import com.positivity.catalog.internal.dto.SupplierPriceImportStatusDto;
import com.positivity.catalog.service.SupplierPriceEntryService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API over append-only supplier price entries (ADR-0053 §1–§2).
 *
 * <p>These are purchasing and margin facts. Displaying vendor cost as context is permitted;
 * feeding it into price calculation is not, and no write endpoint exists here because an entry
 * exists only because a vendor said so (ADR-0053 §4, ADR-0054).
 *
 * <p>Replaces the retired {@code supplier-item-costs} and {@code supplier-costs} surfaces, which
 * served one mutable row per supplier/item with no effective dating, no source document and no
 * market scope.
 */
@Tag(
        name = "Supplier Prices",
        description = "Vendor price entries applied from supplier PRICAT imports: latest applicable cost,"
                + " full cost history, and import application state. Read-only.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/supplier-prices")
public class SupplierPriceEntryController {

    private final SupplierPriceEntryService supplierPriceEntryService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('catalog:supplier_cost:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "catalog:supplier_cost:read"})
    @GetMapping("/latest")
    @EmitEvent(id = "CATALOG_SUPPLIER_PRICE_LATEST", apiVersion = "1")
    @Operation(
            operationId = "getLatestSupplierPrice",
            summary = "Get the Applicable Vendor Price for a Product",
            description = """
            Returns the vendor price that applies to a product in one market as at a day: the greatest \
            effective-from date not after that day, with ties broken by the vendor document's date and then \
            by fetch time so two readers never disagree about today's cost.
            Use this tool for purchasing and margin context; do not use it as a pricing input — supplier cost \
            participates in no sell-price resolution, and the suggested retail price it carries is vendor \
            reference data that must be labelled with its PRICAT source wherever it is shown.
            Preconditions: a PRICAT import must have applied for that vendor, product and market; a product \
            with no vendor price simply has none, which is not an error.
            Required inputs: productId, countryCode and currency; vendorProfileId narrows to one vendor and \
            asOf evaluates a past day, both optional, with asOf defaulting to today.
            Emits a CATALOG_SUPPLIER_PRICE_LATEST event; no state changes.
            Returns 404 when no entry applies, and 400 when a parameter is malformed. Money fields may be \
            null, meaning the vendor did not state that value — null is never zero.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The applicable vendor price entry.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SupplierPriceEntryDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "A parameter is missing or malformed.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No vendor price applies to that product, market and day. The response has NO body:"
                    + " absence is an expected outcome, not an error envelope to parse.",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<SupplierPriceEntryDto> getLatestSupplierPrice(
            @Parameter(
                            description = "Product to price.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @RequestParam
                    @NotNull
                    UUID productId,
            @Parameter(description = "Market of interest (ISO 3166-1 alpha-2).", required = true, example = "SE")
                    @RequestParam
                    @NotNull
                    String countryCode,
            @Parameter(description = "Currency of interest (ISO 4217).", required = true, example = "SEK")
                    @RequestParam
                    @NotNull
                    String currency,
            @Parameter(
                            description = "Restrict to one vendor profile; omit to consider every vendor's entries.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"))
                    @RequestParam(required = false)
                    UUID vendorProfileId,
            @Parameter(
                            description = "Day to evaluate; defaults to today. A past day answers what applied then.",
                            schema = @Schema(type = "string", format = "date", example = "2026-08-14"))
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asOf) {
        return supplierPriceEntryService
                .findLatest(productId, vendorProfileId, countryCode, currency, asOf)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('catalog:supplier_cost:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "catalog:supplier_cost:read"})
    @GetMapping("/history")
    @EmitEvent(id = "CATALOG_SUPPLIER_PRICE_HISTORY", apiVersion = "1")
    @Operation(
            operationId = "listSupplierPriceHistory",
            summary = "List Vendor Price History for a Product",
            description = """
            Returns every vendor price recorded for a product, newest effective date first, including \
            superseded entries with the vendor document and import each came from.
            Use this tool to answer what a vendor charged over time and where a cost came from; do not use it \
            to find today's cost, which is getLatestSupplierPrice and applies the effective-dating rules.
            Preconditions: none; a product with no imported vendor prices returns an empty page.
            Required inputs: productId; vendorProfileId narrows to one vendor, and page and size are optional \
            with size defaulting to 50 and capped at 200.
            Emits a CATALOG_SUPPLIER_PRICE_HISTORY event; no state changes.
            Returns 200 with an empty items array when nothing has been imported, so an empty result is not an \
            error condition, and 400 when the page size is out of range.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "A page of vendor price entries, newest effective date first.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Page.class)))
    @ApiResponse(
            responseCode = "400",
            description = "A parameter is missing, malformed, or the page size is out of range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<SupplierPriceEntryDto>> listSupplierPriceHistory(
            @Parameter(
                            description = "Product whose vendor price history to list.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @RequestParam
                    @NotNull
                    UUID productId,
            @Parameter(
                            description = "Restrict to one vendor profile; omit for every vendor.",
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestParam(required = false)
                    UUID vendorProfileId,
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, 1–200.",
                            schema = @Schema(type = "integer", example = "50", defaultValue = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        return ResponseEntity.ok(
                supplierPriceEntryService.findHistory(productId, vendorProfileId, PageRequest.of(page, size)));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('catalog:supplier_cost:read')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "catalog:supplier_cost:read"})
    @GetMapping("/imports/incomplete")
    @EmitEvent(id = "CATALOG_SUPPLIER_PRICE_IMPORT_GAPS", apiVersion = "1")
    @Operation(
            operationId = "listIncompleteSupplierPriceImports",
            summary = "List Vendor Price Imports This Module Could Not Complete",
            description = """
            Returns PRICAT imports whose completion event declared more chunks than this module applied, newest \
            first, each with the counts that disagree.
            Use this tool when vendor prices look stale or partial; do not use it to check a specific product, \
            which is listSupplierPriceHistory.
            Preconditions: none; an empty list is the healthy state and means every import applied whole.
            Required inputs: none.
            Emits a CATALOG_SUPPLIER_PRICE_IMPORT_GAPS event; no state changes. A re-emit was already requested \
            on the supplier command topic when each gap was detected, so an entry here is a feed that did not \
            self-heal rather than one nobody has chased.
            Returns 200 with an empty array when there are no gaps.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Imports with unapplied chunks, newest first.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = SupplierPriceImportStatusDto.class))))
    public ResponseEntity<List<SupplierPriceImportStatusDto>> listIncompleteSupplierPriceImports() {
        return ResponseEntity.ok(supplierPriceEntryService.findIncompleteImports());
    }
}
