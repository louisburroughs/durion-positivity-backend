package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.TreadDesignService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API over MKCAT tread-design marketing enrichment (CAP-324 #1352).
 *
 * <p>Everything here is vendor-supplied, applied from {@code supplier.catalog.updated} events —
 * never catalog-owned product identity or structure. No write endpoint exists because an
 * enrichment exists only because a vendor said so.
 */
@Tag(
        name = "Tread Design Enrichment",
        description = "Vendor-supplied MKCAT marketing content — names, copy per language, artwork — attached to"
                + " catalog products by matching, not by identifier. Read-only.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/tread-designs")
public class TreadDesignController {

    private final TreadDesignService treadDesignService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.TREAD_DESIGN_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.TREAD_DESIGN_VIEW})
    @GetMapping("/for-product/{productId}")
    @EmitEvent(id = "CATALOG_TREAD_DESIGN_FOR_PRODUCT", apiVersion = "1")
    @Operation(
            operationId = "getTreadDesignForProduct",
            summary = "Get Vendor Tread-Design Enrichment for a Product",
            description = """
            Returns the manufacturer marketing content matched to a product — names, copy per language, artwork —
            distinguishable from catalog-owned product data because it lives here rather than on the product itself.
            Use this tool to show manufacturer marketing copy alongside a product; do not use it as a source for
            any structural or identity field, since a supplier fact never changes what a product is.
            Preconditions: the product must exist and must have matched a tread design; fuzzy matching on vendor
            brand and design names means many products, especially newly priced ones, match nothing yet.
            Required inputs: productId path parameter; there is no request body.
            Emits a CATALOG_TREAD_DESIGN_FOR_PRODUCT event; no state changes.
            Returns 404 when the product does not exist or matches no tread design — an ordinary outcome, not
            an error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The matched design's enrichment.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TreadDesignDto.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The product does not exist, or matches no tread design. The response has NO body.",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<TreadDesignDto> getTreadDesignForProduct(
            @Parameter(description = "Product to look up enrichment for.", required = true) @PathVariable
                    UUID productId) {
        return treadDesignService
                .findForProduct(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.TREAD_DESIGN_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.TREAD_DESIGN_VIEW})
    @GetMapping("/unmatched")
    @EmitEvent(id = "CATALOG_TREAD_DESIGN_UNMATCHED_LIST", apiVersion = "1")
    @Operation(
            operationId = "listUnmatchedTreadDesigns",
            summary = "List Vendor Tread Designs Matched to No Product",
            description = """
            Returns tread designs that fuzzy matching, scoped to each design's own vendor's priced products,
            could not resolve to any catalog product, newest applied first.
            Use this tool to review enrichment a person needs to connect manually; do not use it to look up one
            product's enrichment, which is getTreadDesignForProduct. A design matching nothing is an ordinary
            outcome here, not a failure of ingestion.
            Preconditions: none; an empty result means every applied design has matched at least one product.
            Required inputs: none; page and size are optional, with size defaulting to 50 and capped at 200.
            Emits a CATALOG_TREAD_DESIGN_UNMATCHED_LIST event; no state changes.
            Returns 200 with an empty items array when nothing is unmatched.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "A page of unmatched tread designs, newest applied first.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Page.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The page size is out of range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<TreadDesignDto>> listUnmatchedTreadDesigns(
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, 1-200.",
                            schema = @Schema(type = "integer", example = "50", defaultValue = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        return ResponseEntity.ok(treadDesignService.findUnmatched(PageRequest.of(page, size)));
    }
}
