package com.positivity.domainevents.supplier;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One piece of manufacturer artwork on a catalogue enrichment (CAP-324 #1230, #1257).
 *
 * <h2>A stored reference, never a vendor URL</h2>
 *
 * {@code imageId} points at bytes the platform holds. The vendor's own URL travels as
 * {@code sourceUri} for provenance and is not somewhere to fetch from: rendering a product page
 * from a manufacturer's server puts a third party's uptime and rate limits in front of every view,
 * and gives the platform nothing to cache or optimise.
 *
 * <h2>Unresolved images are still published</h2>
 *
 * When the bytes could not be fetched or stored, {@code imageId} is absent and {@code unresolved} is
 * true. The enrichment goes out regardless, because dropping a whole product's marketing copy over
 * one failed download would be a silent loss of everything else on the record — and the missing
 * picture is retried on its own.
 *
 * @param imageType   the vendor's own classification of the image, as stated
 * @param imageId     pos-image identifier for the stored bytes; absent when unresolved
 * @param contentHash SHA-256 of the stored bytes, so a consumer can tell one picture from another
 *                    without fetching either
 * @param sourceUri   where the vendor published it. Provenance only
 * @param unresolved  whether the bytes are still missing and will be retried
 */
public record SupplierCatalogEnrichmentImage(
        @Nullable String imageType,
        @Nullable Long imageId,
        @Nullable String contentHash,
        @Nullable String sourceUri,
        boolean unresolved) {

    public SupplierCatalogEnrichmentImage {
        if (!unresolved && imageId == null) {
            // A resolved image with nothing to point at is the shape that quietly loses artwork:
            // a consumer would treat it as present and render nothing.
            throw new IllegalArgumentException("a resolved enrichment image must carry an imageId");
        }
    }

    /** An image whose bytes could not be obtained; it will be retried. */
    @NonNull
    public static SupplierCatalogEnrichmentImage unresolved(@Nullable String imageType, @NonNull String sourceUri) {
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        return new SupplierCatalogEnrichmentImage(imageType, null, null, sourceUri, true);
    }
}
