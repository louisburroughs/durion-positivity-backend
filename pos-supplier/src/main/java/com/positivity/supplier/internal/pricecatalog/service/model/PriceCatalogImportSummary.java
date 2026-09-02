package com.positivity.supplier.internal.pricecatalog.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Bookkeeping summary of one vendor price-catalog (PRICAT) import run (ADR-0053 §7).
 *
 * <p>The counters are the completeness contract, and they partition the fetch:
 * {@code matched + unmatched + duplicate == fetched} for every terminal import. An operator reading
 * a non-zero {@code linesUnmatched} is reading how much of a vendor's catalog this deployment
 * currently cannot price, which is a catalog-data task, not an integration failure.
 *
 * <p>This summary carries no prices. Vendor prices reach consumers as
 * {@code supplier.pricecatalog.updated} events and are purchasing input only — nothing here or
 * downstream may feed a sell-price resolver (ADR-0053 §4).
 *
 * @param importManifestId   identity of the import run; the event aggregate id
 * @param vendorProfileId    vendor profile the catalog was fetched for
 * @param bindingId          endpoint binding the run was fetched over; null on rows recorded before
 *                           the binding was persisted and on re-application manifests (#1637)
 * @param supplierRef        profile alias at fetch time; descriptive only, never a key
 * @param status             {@code IN_PROGRESS}, {@code COMPLETED}, {@code EMPTY} or {@code FAILED}
 * @param fetchedAt          when the vendor was called
 * @param completedAt        when staging committed; null while in progress or on a failed fetch
 * @param windowFrom         inclusive start of the incremental retrieval interval requested for the
 *                           run; null for full-snapshot protocols — all current PRICAT protocols
 * @param windowTo           exclusive end of that interval; null for full-snapshot protocols
 * @param checkpointState    opaque continuation state committed for the next incremental run; null
 *                           for full-snapshot protocols
 * @param checkpointAt       when the checkpoint state was committed; null whenever it is null
 * @param linesFetched       lines the vendor sent
 * @param linesMatched       lines resolved to a catalog product and published
 * @param linesUnmatched     lines quarantined for review (ADR-0053 §5)
 * @param linesDuplicate     lines deduplicated within the document
 * @param chunkCount         number of chunk events published for the import
 * @param sourceDocumentId   vendor catalog document id, when stated
 * @param sourceDocumentDate vendor catalog document date, when stated
 * @param countryCode        market the catalog was fetched for, when stated
 * @param currency           currency of the catalog, when stated
 * @param failureDetail      operator-facing failure summary for a failed import; never a credential
 * @param errorCode          stable machine-readable failure category for a failed import,
 *                           complementing {@code failureDetail}; null unless the run failed
 */
@Schema(description = "Bookkeeping summary of one vendor PRICAT import run.")
public record PriceCatalogImportSummary(
        @Schema(
                description = "Identity of the import run; the aggregate id of its events.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID importManifestId,

        @Schema(
                description = "Vendor profile the catalog was fetched for.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID vendorProfileId,

        @Schema(
                description = "Endpoint binding the run was fetched over. Null on runs recorded before the"
                        + " binding was persisted (forward-only) and on quarantine re-application manifests,"
                        + " which make no vendor call.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e")
        @Nullable
        UUID bindingId,

        @Schema(description = "Profile alias at fetch time; descriptive only.", example = "michelin-eu") @NonNull
        String supplierRef,

        @Schema(
                description = "Terminal or in-flight state of the run.",
                example = "COMPLETED",
                allowableValues = {"IN_PROGRESS", "COMPLETED", "EMPTY", "FAILED"})
        @NonNull
        String status,

        @Schema(description = "When the vendor was called.") @NonNull
        Instant fetchedAt,

        @Schema(description = "When staging committed; null while in progress or after a failed fetch.") @Nullable
        Instant completedAt,

        @Schema(
                description = "Inclusive start of the incremental retrieval interval requested for the run."
                        + " Null for full-snapshot protocols — every current PRICAT protocol: B4.0 fetches the"
                        + " vendor's whole catalog, so there is no window.")
        @Nullable
        Instant windowFrom,

        @Schema(description = "Exclusive end of the requested retrieval interval; null for full-snapshot protocols.")
        @Nullable
        Instant windowTo,

        @Schema(
                description = "Opaque continuation state committed for the next incremental run; null for"
                        + " full-snapshot protocols, which have nothing to resume from.")
        @Nullable
        String checkpointState,

        @Schema(description = "When the checkpoint state was committed; null whenever checkpointState is null.")
        @Nullable
        Instant checkpointAt,

        @Schema(description = "Lines the vendor sent.", example = "12500")
        int linesFetched,

        @Schema(description = "Lines resolved to a catalog product and published.", example = "12310")
        int linesMatched,

        @Schema(description = "Lines quarantined for review.", example = "182")
        int linesUnmatched,

        @Schema(description = "Lines deduplicated within the document.", example = "8")
        int linesDuplicate,

        @Schema(description = "Number of chunk events published for the import.", example = "25")
        int chunkCount,

        @Schema(description = "Vendor catalog document id, when stated.", example = "PRICAT-4046266_202305120844")
        @Nullable
        String sourceDocumentId,

        @Schema(description = "Vendor catalog document date, when stated.", example = "2026-05-12") @Nullable
        LocalDate sourceDocumentDate,

        @Schema(description = "Market the catalog was fetched for, when stated.", example = "SE") @Nullable
        String countryCode,

        @Schema(description = "Currency of the catalog, when stated.", example = "SEK") @Nullable
        String currency,

        @Schema(description = "Operator-facing failure summary for a failed import.") @Nullable
        String failureDetail,

        @Schema(
                description = "Stable machine-readable failure category for a failed import, alongside the"
                        + " free-text failureDetail. Null unless the run failed.",
                example = "FETCH_FAILED",
                allowableValues = {"FETCH_FAILED", "DECODE_FAILED"})
        @Nullable
        String errorCode) {

    public PriceCatalogImportSummary {
        Objects.requireNonNull(importManifestId, "importManifestId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
        if (linesFetched < 0 || linesMatched < 0 || linesUnmatched < 0 || linesDuplicate < 0 || chunkCount < 0) {
            throw new IllegalArgumentException("line counters must be >= 0");
        }
    }
}
