package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.pricecatalog.service.SupplierPriceCatalogService;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogFreshnessView;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.internal.pricecatalog.service.model.UnmatchedPriceCatalogLineView;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.model.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vendor price-catalog (PRICAT) import administration (ADR-0053, CAP-318 #1224).
 *
 * <h2>What this API is not</h2>
 *
 * It serves no prices. Vendor price rows reach consumers only as
 * {@code supplier.pricecatalog.updated} events, and that is what keeps supplier cost out of every
 * sell-price resolver (ADR-0053 §4). What is exposed here is bookkeeping — did the import run, how
 * much of it matched, and what is waiting on catalog work.
 *
 * <h2>Two permissions, not one</h2>
 *
 * Reading import bookkeeping is {@code supplier:pricecatalog:read} (bit 448); triggering a run is
 * {@code supplier:pricecatalog:import} (bit 449). A trigger calls a trading partner and publishes an
 * import's worth of events, so it is gated apart from looking at the results.
 */
@Tag(
        name = "Supplier Price Catalog",
        description = "Trigger vendor PRICAT imports and read their bookkeeping and unmatched-line quarantine."
                + " No vendor prices are served here; they travel as supplier.pricecatalog.updated events.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/admin/price-catalog")
public class SupplierPriceCatalogAdminController {

    /** Bound so a caller cannot ask for an unbounded page of a table that grows with every import. */
    private static final String MAX_PAGE_SIZE = "200";

    private static final String UNAUTHENTICATED_DESCRIPTION = "Authentication is missing or the bearer token is"
            + " invalid. The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
            + " status, so clients must not attempt to parse an error envelope here.";

    private final SupplierPriceCatalogService priceCatalogService;

    @Operation(
            operationId = "triggerSupplierPriceCatalogImport",
            summary = "Trigger a vendor price-catalog import",
            description = """
                    Fetches the vendor's current PRICAT document for a profile's buyer account, stages every line, and
                    publishes the matched lines as chunked supplier.pricecatalog.updated events plus one
                    supplier.pricecatalog.import.completed event.
                    Use this tool to pull a catalog now — after a vendor price change or a catalog fix that should
                    make quarantined lines matchable; do not use it to read results, which is listSupplierPriceCatalogImports.
                    Preconditions: the profile must exist and be enabled, its PRICE_CATALOG capability must be bound to
                    a registered codec, and it must have a billing account; the run is synchronous and can take minutes
                    for a large catalog.
                    Required inputs: vendorProfileId (UUIDv7) as a path parameter; there is no request body and no
                    partial-catalog option — B4.0 returns the whole catalog for the buyer account.
                    Emits a SUPPLIER_PRICECATALOG_IMPORT_TRIGGER event, writes exchange-audit rows for the vendor call,
                    and appends staged lines; nothing is ever deleted or overwritten, so a failed or empty fetch leaves
                    the previous import as the latest word on this vendor's prices.
                    Returns 200 with a terminal summary whose status may be FAILED or EMPTY — a vendor outage is
                    reported in the summary rather than as an error — 404 when the profile does not exist, and 409 when
                    the capability is not configured for the profile.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Terminal summary of the run, including FAILED and EMPTY outcomes.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PriceCatalogImportSummary.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No vendor profile exists with the supplied id.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The profile is disabled, or PRICE_CATALOG is not bound to a registered codec.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:pricecatalog:import.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PRICECATALOG_IMPORT + "')")
    @EmitEvent(id = "SUPPLIER_PRICECATALOG_IMPORT_TRIGGER", apiVersion = "1")
    @PostMapping("/{vendorProfileId}/imports")
    public ResponseEntity<PriceCatalogImportSummary> triggerImport(
            @Parameter(
                            description = "Vendor profile to import for (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(priceCatalogService.runImport(vendorProfileId));
    }

    @Operation(
            operationId = "reapplySupplierPriceCatalogQuarantine",
            summary = "Re-apply quarantined price-catalog lines",
            description = """
                    Re-matches the profile's open unmatched-line quarantine against the current product-code replica
                    and applies whatever now resolves, staging it and publishing it exactly as an import would — with
                    no call to the vendor.
                    Use this tool after a catalog fix, or after the product-code replica has been seeded, to close
                    lines that were quarantined for a reason that no longer holds; do not use it to fetch fresh
                    prices, which is triggerSupplierPriceCatalogImport.
                    Preconditions: the profile must exist; lines that carried no identifier and lines whose values
                    never decoded are skipped, because no catalog fix can rescue them.
                    Required inputs: vendorProfileId (UUIDv7) as a path parameter; there is no request body, and the
                    batch size is a deployment setting rather than a caller choice.
                    Emits a SUPPLIER_PRICECATALOG_REAPPLY event and creates one re-application manifest per origin
                    import — a quarantine spans every import that left a line open, and each manifest carries one
                    vendor document's identity — closing the lines it matched and publishing their prices; the
                    original imports' counters are left untouched, because they record what the vendor sent at the time.
                    Returns 200 with one summary per healed import, 204 when nothing matched — the healthy steady
                    state, not a failure — and 404 when the profile does not exist.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "One summary per origin import that had lines resolve.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PriceCatalogImportSummary.class))))
    @ApiResponse(
            responseCode = "204",
            description = "Nothing in the quarantine resolved. The response has NO body: an empty result is the"
                    + " healthy steady state, not an error envelope to parse.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "404",
            description = "No vendor profile exists with the supplied id.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:pricecatalog:import.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PRICECATALOG_IMPORT + "')")
    @EmitEvent(id = "SUPPLIER_PRICECATALOG_REAPPLY", apiVersion = "1")
    @PostMapping("/{vendorProfileId}/quarantine/reapply")
    public ResponseEntity<List<PriceCatalogImportSummary>> reapplyQuarantine(
            @Parameter(
                            description = "Vendor profile whose quarantine to work (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        List<PriceCatalogImportSummary> summaries = priceCatalogService.reapplyQuarantine(vendorProfileId);
        return summaries.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(summaries);
    }

    @Operation(
            operationId = "listSupplierPriceCatalogImports",
            summary = "List vendor price-catalog imports",
            description = """
                    Returns a page of PRICAT import runs for one vendor profile, newest first, including failed and
                    empty runs with their line counters.
                    Use this tool to answer whether a catalog feed is healthy and how much of it matched; do not use
                    it to see which specific lines are waiting on catalog work, which is
                    listSupplierPriceCatalogUnmatchedLines instead.
                    Preconditions: the caller must hold supplier:pricecatalog:read; the profile need not have imported
                    anything, in which case the page is empty.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; page defaults to 0 and size defaults to
                    50 with a maximum of 200. Optional filters: bindingId narrows a profile's history to one endpoint
                    binding (runs recorded before binding ids were persisted carry none and never match), status keeps
                    one run status, and dateFrom/dateTo bound fetchedAt as a half-open window — dateFrom inclusive,
                    dateTo exclusive.
                    Emits a SUPPLIER_PRICECATALOG_IMPORT_LIST event; no state changes and no vendor call is made.
                    Returns 200 with an empty items array when the profile has never imported or nothing matches the
                    filters, so an empty result is not an error, and 400 when the page size is outside the permitted
                    range or a filter value does not parse.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "A page of import summaries, newest first.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Page size outside the permitted range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:pricecatalog:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PRICECATALOG_READ + "')")
    @EmitEvent(id = "SUPPLIER_PRICECATALOG_IMPORT_LIST", apiVersion = "1")
    @GetMapping("/{vendorProfileId}/imports")
    public ResponseEntity<PagedResponse<PriceCatalogImportSummary>> listImports(
            @Parameter(
                            description = "Vendor profile whose imports to list (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description = "Narrow to runs fetched over one endpoint binding (UUIDv7). Runs recorded"
                                    + " before binding ids were persisted carry none and never match this filter.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e"))
                    @RequestParam(required = false)
                    UUID bindingId,
            @Parameter(
                            description = "Narrow to one run status.",
                            schema = @Schema(implementation = PriceCatalogImportStatus.class))
                    @RequestParam(required = false)
                    PriceCatalogImportStatus status,
            @Parameter(
                            description = "Inclusive lower bound on fetchedAt (ISO-8601 instant).",
                            schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    Instant dateFrom,
            @Parameter(
                            description = "Exclusive upper bound on fetchedAt (ISO-8601 instant); the window is"
                                    + " half-open so adjacent windows tile without listing a boundary run twice.",
                            schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    Instant dateTo,
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
                priceCatalogService.listImports(vendorProfileId, bindingId, status, dateFrom, dateTo, page, size));
    }

    @Operation(
            operationId = "listSupplierPriceCatalogUnmatchedLines",
            summary = "List quarantined price-catalog lines",
            description = """
                    Returns a page of PRICAT lines that could not be attached to a catalog product, newest first, each
                    with the reason it was quarantined. By default only open lines are listed — a line stays listed
                    until a later import or re-application matches it — and resolved=true flips the listing to closed
                    lines instead, for auditing what a catalog fix healed.
                    Use this tool to work the catalog-data backlog a vendor catalog exposes; do not use it for the
                    run-level totals those lines roll up into, which is listSupplierPriceCatalogImports instead.
                    Preconditions: the caller must hold supplier:pricecatalog:read.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; page defaults to 0 and size defaults to
                    50 with a maximum of 200. Optional filters: reason keeps one quarantine reason, search is a
                    case-insensitive contains-match over the line's EAN, vendor article code and cross-reference code
                    (LIKE metacharacters are treated literally), and dateFrom/dateTo bound fetchedAt as a half-open
                    window — dateFrom inclusive, dateTo exclusive.
                    Emits a SUPPLIER_PRICECATALOG_UNMATCHED_LIST event; no state changes and no vendor call is made.
                    Returns 200 with an empty items array when the quarantine is clear or nothing matches the filters,
                    and 400 when the page size is outside the permitted range or a filter value does not parse.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "A page of open quarantined lines, newest first.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Page size outside the permitted range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:pricecatalog:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PRICECATALOG_READ + "')")
    @EmitEvent(id = "SUPPLIER_PRICECATALOG_UNMATCHED_LIST", apiVersion = "1")
    @GetMapping("/{vendorProfileId}/unmatched-lines")
    public ResponseEntity<PagedResponse<UnmatchedPriceCatalogLineView>> listUnmatchedLines(
            @Parameter(
                            description = "Vendor profile whose quarantine to list (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description = "Narrow to one quarantine reason.",
                            schema = @Schema(implementation = UnmatchedLineReason.class))
                    @RequestParam(required = false)
                    UnmatchedLineReason reason,
            @Parameter(
                            description = "Case-insensitive contains-match over the line's EAN, vendor article code"
                                    + " and cross-reference code. LIKE metacharacters are treated literally, so a"
                                    + " pasted code containing an underscore matches itself, not a wildcard.",
                            schema = @Schema(type = "string", example = "3528709999083"))
                    @RequestParam(required = false)
                    String search,
            @Parameter(
                            description = "Inclusive lower bound on fetchedAt (ISO-8601 instant).",
                            schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    Instant dateFrom,
            @Parameter(
                            description = "Exclusive upper bound on fetchedAt (ISO-8601 instant); the window is"
                                    + " half-open so adjacent windows tile without listing a boundary line twice.",
                            schema = @Schema(type = "string", format = "date-time"))
                    @RequestParam(required = false)
                    Instant dateTo,
            @Parameter(
                            description = "True lists resolved lines instead of the open worklist. Omitted or false"
                                    + " keeps the default the worklist has always had: open lines only.",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(required = false)
                    Boolean resolved,
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, 1–" + MAX_PAGE_SIZE + ".",
                            schema = @Schema(type = "integer", example = "50", defaultValue = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        return ResponseEntity.ok(priceCatalogService.listUnmatchedLines(
                vendorProfileId, reason, search, dateFrom, dateTo, resolved, page, size));
    }

    @Operation(
            operationId = "getSupplierPriceCatalogFreshness",
            summary = "Read a vendor price-catalog's freshness",
            description = """
                    Returns how fresh one vendor profile's price catalog is: latestEffectiveDate — the newest catalog
                    document date the vendor itself stated on a completed import, which is vendor document metadata —
                    kept strictly apart from lastFetchedAt, the platform's own last retrieval attempt over every run
                    including failed and empty ones. Alongside them: lastCompletedAt, the open unmatched-line count,
                    the backend-configured staleness threshold (ISO-8601 duration) with the stale verdict it implies,
                    and each PRICE_CATALOG binding's schedule plus its scheduler-lease state (checkpointAt,
                    lastRunOutcome, lastRunStartedAt). Window and checkpoint fields are null for full-snapshot
                    protocols — every current PRICAT protocol: B4.0 fetches the vendor's whole catalog, so there is no
                    retrieval window to advance.
                    Use this tool to answer "can these vendor prices be trusted today" in one read; do not use it for
                    run-by-run history, which is listSupplierPriceCatalogImports.
                    Preconditions: the caller must hold supplier:pricecatalog:read; the profile need not have imported
                    anything — a never-fetched profile reports stale=true with null timestamps, not an error.
                    Required inputs: vendorProfileId (UUIDv7) as a path parameter.
                    Emits a SUPPLIER_PRICECATALOG_FRESHNESS_GET event; no state changes and no vendor call is made,
                    and the stale verdict is computed server-side against the returned threshold — catalog-currency
                    policy unrelated to any cache TTL — so every client behaves consistently.
                    Returns 200 with the freshness view, and 404 only when the vendor profile does not exist.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The profile's catalog freshness; a never-imported profile is stale, not an error.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PriceCatalogFreshnessView.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No vendor profile exists with the supplied id.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:pricecatalog:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PRICECATALOG_READ + "')")
    @EmitEvent(id = "SUPPLIER_PRICECATALOG_FRESHNESS_GET", apiVersion = "1")
    @GetMapping("/{vendorProfileId}/freshness")
    public ResponseEntity<PriceCatalogFreshnessView> getFreshness(
            @Parameter(
                            description = "Vendor profile whose catalog freshness to read (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(priceCatalogService.getFreshness(vendorProfileId));
    }
}
