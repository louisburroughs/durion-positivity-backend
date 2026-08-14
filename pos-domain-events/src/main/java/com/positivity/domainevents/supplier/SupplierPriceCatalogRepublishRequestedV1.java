package com.positivity.domainevents.supplier;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.pricecatalog.republish.requested} v1 on
 * {@code supplier.commands.v1} (ADR-0044 §4 reconciliation).
 *
 * <p>Published by a consumer that applied fewer chunks than the import's completion event declared.
 * It is a <strong>request to the owner</strong>, not a query: ADR-0044 §4 recovers gaps through the
 * owner's command topic precisely so that a consumer never holds a synchronous dependency on the
 * producer to be correct.
 *
 * <p>The request names the import, not the missing lines, because the consumer cannot know what it
 * never received — only how many chunks it is short. The owner re-emits the import's chunk events,
 * and an over-broad re-emit is safe while an under-broad one is not.
 *
 * <p><strong>Safe on the consumer's side, not on the event id.</strong> A re-emit republishes the
 * chunks as new events with new {@code eventId}s, so the ordinary event-id guard does not fire for
 * them. A consumer of these events must therefore deduplicate on the chunk's own identity —
 * {@code (importManifestId, chunkSequence)} — or a recovery will write a second copy of every line
 * it re-delivers. pos-catalog keeps an applied-chunk log for exactly this reason.
 *
 * @param importManifestId the import to re-emit
 * @param vendorProfileId  vendor profile the import belongs to, for the owner's routing and logs
 * @param chunksApplied    how many chunk events the requester applied
 * @param expectedChunks   how many the completion event declared
 * @param requestedBy      service name of the requesting consumer, e.g. {@code pos-catalog}
 * @param reason           short operator-facing explanation; never a payload dump
 */
public record SupplierPriceCatalogRepublishRequestedV1(
        @NonNull UUID importManifestId,
        @NonNull UUID vendorProfileId,
        int chunksApplied,
        int expectedChunks,
        @NonNull String requestedBy,
        @Nullable String reason) {

    /** Event type of this payload on {@code supplier.commands.v1}. */
    public static final String EVENT_TYPE = "supplier.pricecatalog.republish.requested";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;
}
