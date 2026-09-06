package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.mktcat.service.MktCatImporter;
import com.positivity.supplier.internal.mktcat.service.MktCatStagedReader;
import com.positivity.supplier.internal.mktcat.service.model.MarketingEnrichmentView;
import com.positivity.supplier.internal.security.SupplierPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketing catalogue (MKCAT) enrichment (CAP-324 #1230, #1257).
 *
 * <p>The scheduled sweep covers the ordinary case. These exist for the two moments it does not:
 * bringing a newly configured catalogue in without waiting for its first cron firing, and looking at
 * what the catalogue actually sent when a product's marketing copy is not what somebody expected.
 */
@RestController
@RequestMapping("/v1/supplier/mktcat")
@RequiredArgsConstructor
@Tag(name = "Supplier Marketing Catalog", description = "Manufacturer marketing enrichment (MKCAT C1.2)")
public class SupplierMarketingCatalogController {

    private final MktCatImporter importer;
    private final MktCatStagedReader stagedReader;

    @PostMapping("/{supplierRef}/imports")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:mktcat:import"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.MARKETING_CATALOG_IMPORT + "')")
    @EmitEvent(id = "SUPPLIER_MKTCAT_IMPORT", apiVersion = "1")
    @Operation(
            operationId = "importMarketingCatalog",
            summary = "Import The Marketing Catalog",
            description = """
                    Reads the manufacturer marketing catalogue and publishes enrichment for every tread design \
                    variant whose content has changed since the last run.
                    Use this tool to bring a newly configured catalogue in without waiting for its first \
                    scheduled sweep, or after fixing a configuration problem; do not use it to force \
                    republication of unchanged content, because publication is decided by content and an \
                    unchanged variant emits nothing however often it is imported.
                    Preconditions: the vendor profile must be enabled and have a marketing-catalogue binding \
                    configured.
                    Required inputs: supplierRef path parameter.
                    Emits a SUPPLIER_MKTCAT_IMPORT event, and for each changed variant publishes \
                    supplier.catalog.updated for the catalog domain to attach.
                    Returns 200 with how many variants were seen, published and skipped; 404 when the vendor \
                    profile is unknown; 409 when the profile is disabled or has no marketing-catalogue binding; \
                    and 422 when the catalogue could not be read at all.
                    """,
            tags = {"Supplier Marketing Catalog"})
    @ApiResponse(responseCode = "200", description = "What the import saw and published")
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Vendor profile disabled, or no marketing-catalogue binding configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The catalogue could not be read",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Map<String, Object>> importMarketingCatalog(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef) {
        MktCatImporter.ImportOutcome outcome = importer.importAll(new SupplierRef(supplierRef));
        // Three numbers because they answer different questions: seen says whether the catalogue had
        // anything, published how much of it changed, and skipped whether anything was unreadable.
        // An import reporting 4000 seen and 0 published is working exactly as intended.
        return ResponseEntity.ok(Map.of(
                "supplierRef",
                supplierRef,
                "seen",
                outcome.seen(),
                "published",
                outcome.published(),
                "skipped",
                outcome.skipped()));
    }

    @GetMapping("/{supplierRef}/variants")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:mktcat:import"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.MARKETING_CATALOG_IMPORT + "')")
    @EmitEvent(id = "SUPPLIER_MKTCAT_VARIANT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listMarketingCatalogVariants",
            summary = "List Staged Marketing Enrichment",
            description = """
                    Lists the tread design variants a marketing catalogue last sent, most recently seen first, \
                    including which of them are still missing artwork.
                    Use this tool when a product's marketing copy is not what was expected, to see what the \
                    catalogue actually published; do not read this as catalog content, because nothing here has \
                    been attached to a product and a variant may match no product at all. This list is \
                    supplier-scoped staged enrichment — one vendor's own catalogue submission — and is NOT the \
                    unmatched-product queue: that worklist, matched by design against products, is pos-catalog's \
                    listUnmatchedTreadDesigns.
                    Preconditions: none.
                    Required inputs: supplierRef path parameter; limit defaults to 100. Optional \
                    hasUnresolvedImages filters to variants still missing artwork (true) or not (false); \
                    omitted, every staged row is returned regardless of image state.
                    Emits a SUPPLIER_MKTCAT_VARIANT_LIST event.
                    Returns 200 with the staged variants, which is an empty list when the catalogue has never \
                    been imported or nothing matches the filter, 400 when hasUnresolvedImages is not a boolean, and \
                    404 when the vendor profile is unknown.
                    """,
            tags = {"Supplier Marketing Catalog"})
    @ApiResponse(responseCode = "200", description = "Staged enrichment for this catalogue")
    @ApiResponse(
            responseCode = "400",
            description = "hasUnresolvedImages is not a boolean",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<MarketingEnrichmentView>> listMarketingCatalogVariants(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef,
            @Parameter(description = "Maximum rows to return") @RequestParam(defaultValue = "100") int limit,
            @Parameter(
                            description = "Filter to variants still missing artwork (true) or not (false); omitted"
                                    + " returns every staged row regardless of image state. This is a"
                                    + " supplier-scoped filter, not the pos-catalog unmatched-product queue.")
                    @RequestParam(required = false)
                    Boolean hasUnresolvedImages) {
        return ResponseEntity.ok(stagedReader.findStaged(new SupplierRef(supplierRef), limit, hasUnresolvedImages));
    }
}
