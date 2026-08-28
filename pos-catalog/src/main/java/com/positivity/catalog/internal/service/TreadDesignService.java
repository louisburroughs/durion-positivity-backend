package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.TreadDesignDto;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read access to MKCAT tread-design enrichment (CAP-324 #1352).
 *
 * <p>Everything returned here is vendor-supplied marketing content, never catalog-owned product
 * data — this module never writes to a product's identity or structural fields from it. No write
 * method exists here for the same reason {@code SupplierPriceEntryService} has none: an enrichment
 * exists only because a vendor said so, applied from {@code supplier.catalog.updated} events.
 */
public interface TreadDesignService {

    /**
     * The enrichment matched to a product, when one has been resolved.
     *
     * @param productId the catalog product
     * @return the design's enrichment, or empty when the product matches no design
     */
    @NonNull
    Optional<TreadDesignDto> findForProduct(@NonNull UUID productId);

    /**
     * Designs that currently match no product, newest first — an ordinary outcome (fuzzy matching
     * on brand and design names produces misses), surfaced for manual review rather than dropped.
     *
     * @param pageable paging
     * @return a page of unmatched designs
     */
    @NonNull
    Page<TreadDesignDto> findUnmatched(@NonNull Pageable pageable);
}
