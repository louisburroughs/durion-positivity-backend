package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.stockreport.updated} v1 on {@code supplier.events.v1}
 * (ADR-0049 §3, CAP-322).
 *
 * <p>Published by pos-supplier as one chunk of a vendor stock snapshot, for pos-inventory to hold
 * as <strong>supplier availability hints</strong>. Hints are not owned stock: nothing here may
 * enter inventory valuation or on-hand availability-to-promise, because it describes a vendor's
 * warehouse rather than ours.
 *
 * <h2>Two timestamps, deliberately</h2>
 *
 * {@code snapshotAsOf} is the vendor's own statement of when the snapshot was taken; {@code
 * fetchedAt} is when we asked. A report fetched at noon may describe stock as of 06:00, and
 * staleness must be judged against the vendor's figure — the fetch time only says when we last
 * heard, not how fresh the answer was.
 *
 * <h2>Absence is not zero</h2>
 *
 * A snapshot lists what the vendor chose to report. An article that appears with no quantity, and
 * an article that does not appear at all, are both "no data" — neither means the vendor has none.
 * Only an explicit zero means out of stock, and {@link SupplierStockReportLine} keeps that
 * distinction rather than leaving it to each consumer to rediscover.
 *
 * @param snapshotId      identity of this snapshot; the event aggregate id
 * @param vendorProfileId vendor profile that produced it (ADR-0050 §1 identity)
 * @param supplierRef     profile alias at fetch time; descriptive only
 * @param chunkSequence   1-based sequence of this chunk within the snapshot
 * @param chunkCount      total chunks in the snapshot, so a consumer can detect a gap without a
 *                        second event type
 * @param documentId      vendor document id, when stated
 * @param buyerAccountNumber buyer account the snapshot was produced for
 * @param countryCode     market the snapshot covers, when the profile states one
 * @param snapshotAsOf    vendor-stated snapshot instant; null when the vendor stated no time
 * @param fetchedAt       when the producer fetched the document
 * @param linesReported   total lines in the whole snapshot, across every chunk
 * @param lines           the lines carried by this chunk; never empty for a published chunk
 */
public record SupplierStockReportUpdatedV1(
        @NonNull UUID snapshotId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        int chunkSequence,
        int chunkCount,
        @Nullable String documentId,
        @NonNull String buyerAccountNumber,
        @Nullable String countryCode,
        @Nullable Instant snapshotAsOf,
        @NonNull Instant fetchedAt,
        int linesReported,
        @NonNull List<SupplierStockReportLine> lines) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.stockreport.updated";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;
}
