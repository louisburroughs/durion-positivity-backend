package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.stockreport.service.SupplierStockSnapshotService;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotLineView;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to fetched vendor stock-report snapshots (CAP-322; issue #1638 decision 5).
 *
 * <h2>What this API is not</h2>
 *
 * It never contacts a vendor. A snapshot is what the scheduled stock-report fetch already stored —
 * the vendor's own statement of its stock, for a whole market, at one moment. The live question
 * "what does the vendor hold right now" is the stock inquiry (ADR-0044), a different act under a
 * different permission, because reading a stored document and placing a call to a trading partner
 * are different costs.
 *
 * <h2>Two-step browse, by snapshot id</h2>
 *
 * Latest-snapshot resolution and line paging are separate endpoints on purpose: the metadata read
 * hands out an immutable {@code snapshotId}, and every line page is addressed under it, so all
 * pages of one browse describe one snapshot even when a newer report lands mid-browse.
 */
@Tag(
        name = "Supplier Stock Snapshots",
        description = "Read fetched vendor stock-report snapshots: latest-snapshot metadata per vendor profile, and"
                + " paged lines of one immutable snapshot. No endpoint here contacts a vendor.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/vendor-profiles/{vendorProfileId}/stock-snapshots")
public class SupplierStockSnapshotController {

    private static final String UNAUTHENTICATED_DESCRIPTION = "Authentication is missing or the bearer token is"
            + " invalid. The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
            + " status, so clients must not attempt to parse an error envelope here.";

    private final SupplierStockSnapshotService stockSnapshotService;

    @Operation(
            operationId = "getLatestSupplierStockSnapshot",
            summary = "Get a vendor profile's latest stock-snapshot metadata",
            description = """
                    Returns the metadata of the profile's newest stock snapshot — newest by the vendor-stated
                    snapshotAsOf, never by fetch time — without any lines.
                    The two clocks matter: snapshotAsOf and issuedOn are the VENDOR's claims about the vendor's own
                    moment, while fetchedAt and completedAt are this platform's record of when it asked and finished
                    storing the answer. Staleness of the stock picture is judged against snapshotAsOf — a report
                    fetched a minute ago can describe yesterday's warehouse — and snapshots carrying no vendor-stated
                    instant (a failed or unparseable fetch) never outrank one that has one.
                    Use this tool to resolve the snapshotId to browse and to judge how fresh and complete the latest
                    report is; do not use it to ask what the vendor holds right now, which is the live stock inquiry
                    under supplier:stock:inquire.
                    Preconditions: the caller must hold supplier:stocksnapshot:read.
                    Required inputs: vendorProfileId (UUIDv7) as a path parameter; there is no request body.
                    Emits a SUPPLIER_STOCK_SNAPSHOT_GET event; read-only, and no vendor call is made.
                    Returns 200 with the metadata, or 404 when the profile has no snapshot — including for an unknown
                    vendorProfileId, since snapshots deliberately outlive profile configuration and are the only
                    record consulted here.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The latest snapshot's metadata, without lines.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StockSnapshotSummary.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No stock snapshot exists for the profile (or no such profile is known).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks supplier:stocksnapshot:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/latest")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.STOCK_SNAPSHOT_READ + "')")
    @EmitEvent(id = "SUPPLIER_STOCK_SNAPSHOT_GET", apiVersion = "1")
    public ResponseEntity<StockSnapshotSummary> getLatestSnapshot(
            @Parameter(
                            description = "Vendor profile whose latest snapshot to read (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    UUID vendorProfileId) {
        return ResponseEntity.ok(stockSnapshotService.getLatestSnapshot(vendorProfileId));
    }

    @Operation(
            operationId = "listSupplierStockSnapshotLines",
            summary = "List the lines of one stock snapshot",
            description = """
                    Returns one page of a snapshot's lines, in the order the vendor's document stated them.
                    Pages are addressed by the immutable snapshotId rather than by "latest" on purpose: a snapshot is
                    append-only and never changes, so every page of one browse describes the same snapshot even if a
                    newer report arrives mid-browse — paging "latest" directly would silently switch documents
                    between pages, so resolve the id with getLatestSupplierStockSnapshot first.
                    A line's availableQuantity is nullable and the nullability is the contract: null means the vendor
                    reported the article WITHOUT stating a quantity, zero means it explicitly reported none.
                    Use this tool to browse or look an article up in what the vendor last reported; do not use it for
                    a live availability check, which is the stock inquiry under supplier:stock:inquire.
                    Preconditions: the caller must hold supplier:stocksnapshot:read and the snapshot must exist under
                    the given vendor profile.
                    Required inputs: vendorProfileId and snapshotId (UUIDv7) as path parameters; optional search is
                    matched case-insensitively as a contains-match against the article EAN, the vendor's article
                    code and the description, and page defaults to 0 with size 50, at most 200.
                    Emits a SUPPLIER_STOCK_SNAPSHOT_LINES_LIST event; read-only, and no vendor call is made.
                    Returns 200 with an empty items array when nothing matches the search, 404 when the snapshot does
                    not exist or does not belong to the given vendorProfileId, and 400 when the page size is outside
                    the permitted range.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "One page of snapshot lines, in document order; empty when nothing matches the search.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Page size outside the permitted range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The snapshot does not exist, or belongs to a different vendor profile.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED_DESCRIPTION,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks supplier:stocksnapshot:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{snapshotId}/lines")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.STOCK_SNAPSHOT_READ + "')")
    @EmitEvent(id = "SUPPLIER_STOCK_SNAPSHOT_LINES_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<StockSnapshotLineView>> listSnapshotLines(
            @Parameter(
                            description = "Vendor profile the snapshot must belong to (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    UUID vendorProfileId,
            @Parameter(
                            description = "Immutable snapshot to page (UUIDv7), from getLatestSupplierStockSnapshot.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"))
                    @PathVariable
                    UUID snapshotId,
            @Parameter(
                            description = "Case-insensitive contains-match against the article EAN, the vendor's"
                                    + " article code and the description. Blank is treated as absent.",
                            schema = @Schema(type = "string", example = "3528709999083"))
                    @RequestParam(required = false)
                    String search,
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
                stockSnapshotService.listSnapshotLines(vendorProfileId, snapshotId, search, page, size));
    }
}
