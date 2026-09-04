package com.positivity.supplier.internal.mktcat.service.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Bookkeeping summary of one vendor marketing-catalog (MKCAT) fetch. Placeholder-level for the
 * CAP-317 foundation slice; grows with CAP-324.
 *
 * @param vendorProfileId vendor profile the catalog was fetched for
 * @param fetchedAt when the fetch completed
 * @param articleCount number of catalog articles in the snapshot; {@code >= 0}
 */
public record MarketingCatalogSnapshot(
        @NonNull UUID vendorProfileId, @NonNull Instant fetchedAt, int articleCount) {

    // Left as IllegalArgumentException (#1694): built server-side from an internal fetch outcome,
    // never from client input. A violation here is this module's own defect, so it belongs on the
    // platform 500 fallback, not a client 4xx.
    public MarketingCatalogSnapshot {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
        if (articleCount < 0) {
            throw new IllegalArgumentException("articleCount must be >= 0");
        }
    }
}
