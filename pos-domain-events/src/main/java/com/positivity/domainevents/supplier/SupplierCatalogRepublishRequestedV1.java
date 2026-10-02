package com.positivity.domainevents.supplier;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.catalog.republish.requested} v1 on {@code supplier.commands.v1}
 * (ADR-0044 §4 administrative re-emit-all).
 *
 * <p>Asks the owner to re-emit every MKCAT tread design variant it has staged for one vendor
 * profile, as {@code supplier.catalog.updated} events. It is a <strong>request to the owner</strong>,
 * not a query, for the reason its PRICAT counterpart
 * ({@link SupplierPriceCatalogRepublishRequestedV1}) is: a consumer that lost enrichments cannot
 * fetch them, and must never hold a synchronous dependency on the producer to be correct.
 *
 * <p>The request names the vendor profile, not the missing variants, because a consumer cannot know
 * what it never received (#2356: enrichments recorded as ignored by a second consumer group left no
 * trace of which designs they were). The owner re-emits everything it holds for the profile, and an
 * over-broad re-emit is safe while an under-broad one is not.
 *
 * <p><strong>Safe on the consumer's side, not on the event id.</strong> A re-emit republishes each
 * variant as a new event with a new {@code eventId} and its stored {@code contentHash}, so the
 * ordinary event-id guard does not fire for it. A consumer of these events must therefore treat an
 * unchanged {@code contentHash} for a {@code (vendorProfileId, vendorVariantId)} it already holds as
 * a no-op, or a recovery will re-run matching and disturb review decisions on every design it
 * re-delivers. pos-catalog's enrichment handler does exactly that.
 *
 * <p>The tenant is not in the payload: like every command it travels on the record header and the
 * envelope (ADR-0062 §3), and the owner re-emits only that tenant's staged variants.
 *
 * @param vendorProfileId vendor profile whose staged variants are to be re-emitted
 * @param requestedBy     service or operator asking, e.g. {@code pos-catalog}
 * @param reason          short operator-facing explanation; never a payload dump
 */
public record SupplierCatalogRepublishRequestedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String requestedBy,
        @Nullable String reason) {

    /** Event type of this payload on {@code supplier.commands.v1}. */
    public static final String EVENT_TYPE = "supplier.catalog.republish.requested";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public SupplierCatalogRepublishRequestedV1 {
        // Without a profile there is nothing to scope the re-emit to; refused at the boundary so a
        // malformed command is recorded as one rather than read as "this profile has nothing staged".
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
    }
}
