package com.positivity.supplier.internal.stockreport.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Metadata of one vendor stock-report snapshot fetch (CAP-322; issue #1638 decision 5).
 *
 * <p>Deliberately metadata only — no lines. A snapshot can carry tens of thousands of lines, and
 * they are served separately, paged, addressed by the immutable {@code snapshotId} this summary
 * hands out; see {@code StockSnapshotLineView}.
 *
 * <h2>Two clocks, on purpose</h2>
 *
 * {@code snapshotAsOf} and {@code issuedOn} are the <em>vendor's</em> claims about the vendor's own
 * moment; {@code fetchedAt} and {@code completedAt} are this platform's record of when it asked and
 * finished storing the answer. Staleness of the stock picture is judged against what the vendor
 * said ({@code snapshotAsOf}), never against when we happened to ask — a report fetched a minute
 * ago can describe yesterday's warehouse.
 *
 * @param snapshotId immutable snapshot identity (UUIDv7); the handle the lines endpoint pages by
 * @param vendorProfileId vendor profile the report was fetched for
 * @param supplierRef profile alias at fetch time; descriptive only, never a key
 * @param buyerAccountNumber buyer account the vendor answered for; the report's commercial scope
 * @param countryCode market the report covers, when the vendor scopes reports by country
 * @param status {@code COMPLETED}, {@code EMPTY}, {@code REJECTED} or {@code FAILED}
 * @param documentId vendor document id, when the vendor stated one
 * @param issuedOn vendor-stated issue date of the document, when stated
 * @param snapshotAsOf vendor-stated instant the stock picture describes, when stated
 * @param fetchedAt when this platform called the vendor
 * @param completedAt when storing the snapshot finished; null while in progress or after a failure
 * @param linesReported lines the vendor reported
 * @param linesRejected lines that did not decode
 * @param protocolVersion norm version the fetch used
 */
@Schema(description = "Metadata of one vendor stock-report snapshot fetch; lines are served separately, paged.")
public record StockSnapshotSummary(
        @Schema(
                description = "Immutable snapshot identity; the handle the lines endpoint pages by.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID snapshotId,

        @Schema(
                description = "Vendor profile the report was fetched for.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID vendorProfileId,

        @Schema(description = "Profile alias at fetch time; descriptive only.", example = "michelin-eu") @NonNull
        String supplierRef,

        @Schema(
                description = "Buyer account the vendor answered for; the report's commercial scope.",
                example = "4046266")
        @NonNull
        String buyerAccountNumber,

        @Schema(description = "Market the report covers, when the vendor scopes reports by country.", example = "DE")
        @Nullable
        String countryCode,

        @Schema(
                description = "Outcome of the fetch. A FAILED or EMPTY snapshot is a recorded answer, not an error.",
                example = "COMPLETED",
                allowableValues = {"COMPLETED", "EMPTY", "REJECTED", "FAILED"})
        @NonNull
        String status,

        @Schema(description = "Vendor document id, when the vendor stated one.", example = "STOCK-4046266") @Nullable
        String documentId,

        @Schema(description = "Vendor-stated issue date of the document (vendor time).", example = "2026-08-13")
        @Nullable
        LocalDate issuedOn,

        @Schema(
                description = "Vendor-stated instant the stock picture describes (vendor time). Staleness is judged"
                        + " against this, never against fetchedAt.")
        @Nullable
        Instant snapshotAsOf,

        @Schema(description = "When this platform called the vendor (platform time).") @NonNull
        Instant fetchedAt,

        @Schema(description = "When storing the snapshot finished (platform time); null after a failure.") @Nullable
        Instant completedAt,

        @Schema(description = "Lines the vendor reported.", example = "12500")
        int linesReported,

        @Schema(description = "Lines that did not decode.", example = "3")
        int linesRejected,

        @Schema(description = "Norm version the fetch used.", example = "EDIWHEEL_B-2.1") @NonNull
        String protocolVersion) {

    public StockSnapshotSummary {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(buyerAccountNumber, "buyerAccountNumber must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
        Objects.requireNonNull(protocolVersion, "protocolVersion must not be null");
        if (linesReported < 0 || linesRejected < 0) {
            throw new IllegalArgumentException("line counters must be >= 0");
        }
    }
}
