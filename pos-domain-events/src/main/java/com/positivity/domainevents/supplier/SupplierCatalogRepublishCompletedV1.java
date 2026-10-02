package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code supplier.catalog.republish.completed} v1 on {@code supplier.events.v1}
 * (ADR-0044 §4).
 *
 * <p>Terminal event of an MKCAT re-publication ({@link SupplierCatalogRepublishRequestedV1}): it
 * states how many tread design variants the owner holds for the vendor profile and has just
 * re-emitted. A consumer compares that against the designs it holds for the same profile, which is
 * the only place the two sides' counts ever meet — a lost enrichment is otherwise invisible, because
 * a design that never arrived looks exactly like a design the vendor never published (#2356).
 *
 * <p><strong>The comparison is not ordered against the variants it counts.</strong> Each
 * {@code supplier.catalog.updated} event is keyed on its own staged variant and this event on the
 * vendor profile, so on a topic with more than one partition this event can be consumed while
 * re-emitted variants are still in flight on the others. A shortfall seen at that moment may close
 * by itself; one that survives a second re-publication is real.
 *
 * @param vendorProfileId vendor profile that was re-emitted; also the envelope aggregate id
 * @param supplierRef     that profile's alias as staged; descriptive, never a key
 * @param variantCount    variants the owner holds for the profile, each re-emitted in this run
 * @param requestedBy     who asked for the re-publication, echoed from the request
 * @param completedAt     when the re-publication was queued
 */
public record SupplierCatalogRepublishCompletedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        int variantCount,
        @NonNull String requestedBy,
        @NonNull Instant completedAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.catalog.republish.completed";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;
}
