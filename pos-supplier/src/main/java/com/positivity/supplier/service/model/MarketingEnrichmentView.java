package com.positivity.supplier.service.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What the marketing catalogue last published for one tread design variant (CAP-324 #1230).
 *
 * <p>An operator-facing view of staged enrichment. It carries {@code unresolvedImages} because that
 * is the state somebody has to act on: a variant whose artwork never arrived looks identical to one
 * that has none, and only one of those is a problem.
 *
 * @param vendorVariantId  the catalogue's own variant identifier
 * @param supplierRef      the catalogue profile it came from
 * @param brand            as stated
 * @param treadDesign      primary design name as stated
 * @param productName      the vendor's product name, where given
 * @param seasonality      as stated; not mapped to a Durion vocabulary
 * @param unresolvedImages whether artwork is still missing and being retried
 * @param firstSeenAt      when this variant was first seen
 * @param lastSeenAt       when the catalogue last offered it, changed or not
 * @param lastPublishedAt  when its content last changed and was published
 */
public record MarketingEnrichmentView(
        @NonNull String vendorVariantId,
        @NonNull String supplierRef,
        @Nullable String brand,
        @Nullable String treadDesign,
        @Nullable String productName,
        @Nullable String seasonality,
        boolean unresolvedImages,
        @NonNull Instant firstSeenAt,
        @NonNull Instant lastSeenAt,
        @Nullable Instant lastPublishedAt) {

    public MarketingEnrichmentView {
        Objects.requireNonNull(vendorVariantId, "vendorVariantId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }
}
