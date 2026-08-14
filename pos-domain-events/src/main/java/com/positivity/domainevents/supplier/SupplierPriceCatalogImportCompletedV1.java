package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.pricecatalog.import.completed} v1 on {@code supplier.events.v1}
 * (ADR-0053 §7).
 *
 * <p>Terminal event of a PRICAT import: it is what turns "I have applied some chunks" into "I have
 * applied all of them". A consumer compares the chunks it applied against {@link #chunkCount} and,
 * on a gap, asks the owner to re-emit via the command topic (ADR-0044 §4) — never by calling
 * pos-supplier synchronously.
 *
 * <p>The counters partition the fetch: {@code linesMatched + linesUnmatched + linesDuplicate ==
 * linesFetched}. Unmatched lines are deliberately not in any chunk (they have no product to apply
 * to); their count is published so an operator can see how much of a vendor catalog the deployment
 * is currently blind to.
 *
 * <p>A {@code status} of {@code EMPTY} means the vendor answered with no lines. That is a valid
 * outcome and must never be treated as a signal to delete anything: a failed or empty fetch never
 * destructively replaces existing data.
 *
 * @param importManifestId   the completed import; also the envelope aggregate id
 * @param vendorProfileId    vendor profile that produced the import
 * @param supplierRef        profile alias at fetch time; descriptive only
 * @param status             {@code COMPLETED} or {@code EMPTY}
 * @param linesFetched       lines the vendor sent
 * @param linesMatched       lines resolved to a catalog product and published in chunks
 * @param linesUnmatched     lines quarantined for review (ADR-0053 §5)
 * @param linesDuplicate     lines deduplicated within this document, with the discrepancy logged
 * @param chunkCount         number of chunk events published for this import
 * @param chunkSize          configured maximum number of lines per chunk
 * @param contentChecksum    checksum over the published lines, so a consumer can prove parity
 * @param sourceDocumentId   vendor document id, when stated
 * @param sourceDocumentDate vendor document date, when stated
 * @param buyerAccountNumber buyer account the catalog was requested for
 * @param countryCode        ISO 3166-1 alpha-2 market of the catalog
 * @param currency           ISO 4217 currency of the catalog
 * @param fetchedAt          when the producer fetched the document
 * @param completedAt        when staging committed
 */
public record SupplierPriceCatalogImportCompletedV1(
        @NonNull UUID importManifestId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String status,
        int linesFetched,
        int linesMatched,
        int linesUnmatched,
        int linesDuplicate,
        int chunkCount,
        int chunkSize,
        @Nullable String contentChecksum,
        @Nullable String sourceDocumentId,
        @Nullable LocalDate sourceDocumentDate,
        @NonNull String buyerAccountNumber,
        @Nullable String countryCode,
        @Nullable String currency,
        @NonNull Instant fetchedAt,
        @NonNull Instant completedAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.pricecatalog.import.completed";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;
}
