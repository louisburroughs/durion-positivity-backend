package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogLine;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The single place a PRICAT chunk, completion, or envelope is built (ADR-0049 §3).
 *
 * <h2>Why one place</h2>
 *
 * Three paths publish these events: an ordinary import, a quarantine re-application, and a re-emit
 * requested by a consumer. A re-emit in particular has to produce the <em>same</em> payload as the
 * original publication — the consumer verifies an import against a content checksum and applies
 * chunks keyed on their sequence, so a field present on one path and missing on another would show
 * up as a mismatch a long way from its cause.
 *
 * <p>That is not hypothetical: #1224's product codes were nearly missed because a second
 * serialisation path drifted from the first. Every field a consumer sees is read off the manifest
 * row here, so a new field reaches all three paths at once or none of them.
 */
final class PriceCatalogEventFactory {

    private PriceCatalogEventFactory() {}

    /** A chunk payload described entirely by the manifest row plus this chunk's lines. */
    @NonNull
    static SupplierPriceCatalogUpdatedV1 chunkPayload(
            @NonNull PriceCatalogImportEntity manifest,
            int sequence,
            int chunkCount,
            @NonNull List<SupplierPriceCatalogLine> lines) {
        return new SupplierPriceCatalogUpdatedV1(
                manifest.getImportManifestId(),
                manifest.getVendorProfileId(),
                manifest.getSupplierRef(),
                sequence,
                chunkCount,
                manifest.getSourceDocumentId(),
                manifest.getSourceDocumentDate(),
                manifest.getBuyerAccountNumber(),
                manifest.getCountryCode(),
                manifest.getCurrency(),
                manifest.getProtocolVersion(),
                manifest.getFetchedAt(),
                lines);
    }

    /**
     * The completion payload for a manifest.
     *
     * @param completedAt when the import finished — the manifest's own completion instant, not the
     *                    instant of a later re-emit: re-publishing a fact must not restate when it
     *                    happened
     */
    @NonNull
    static SupplierPriceCatalogImportCompletedV1 completionPayload(
            @NonNull PriceCatalogImportEntity manifest, @NonNull Instant completedAt) {
        return new SupplierPriceCatalogImportCompletedV1(
                manifest.getImportManifestId(),
                manifest.getVendorProfileId(),
                manifest.getSupplierRef(),
                manifest.getStatus().name(),
                manifest.getLinesFetched(),
                manifest.getLinesMatched(),
                manifest.getLinesUnmatched(),
                manifest.getLinesDuplicate(),
                manifest.getChunkCount(),
                manifest.getChunkSize(),
                manifest.getContentChecksum(),
                manifest.getSourceDocumentId(),
                manifest.getSourceDocumentDate(),
                manifest.getBuyerAccountNumber(),
                manifest.getCountryCode(),
                manifest.getCurrency(),
                manifest.getFetchedAt(),
                completedAt);
    }

    /**
     * Wraps a payload for the outbox.
     *
     * <p>{@code aggregateId} is the import manifest, so every event of one import keys to one
     * partition and chunks cannot overtake the completion that closes them. A re-emit deliberately
     * mints a <strong>new</strong> {@code eventId}: it is a new delivery of an old fact, and
     * pretending otherwise would make the consumer's event-id guard swallow the recovery.
     */
    @NonNull
    static <T> DomainEventEnvelope<T> envelope(
            @NonNull PriceCatalogImportEntity manifest,
            @NonNull String eventType,
            long aggregateVersion,
            @NonNull Instant occurredAt,
            @NonNull T payload) {
        return new DomainEventEnvelope<>(
                UUIDv7Generator.generate(),
                eventType,
                1,
                manifest.getImportManifestId(),
                aggregateVersion,
                occurredAt,
                "pos-supplier",
                manifest.getCorrelationId(),
                manifest.getCreatedBy(),
                payload);
    }
}
