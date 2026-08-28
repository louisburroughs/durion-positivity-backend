package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.SupplierPriceEntryDto;
import com.positivity.catalog.internal.dto.SupplierPriceImportStatusDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read access to append-only supplier price entries (ADR-0053 §1–§2).
 *
 * <p>This is purchasing and margin context, not pricing input. ADR-0053 §4 and ADR-0054 keep
 * supplier cost out of every sell-price resolver, and the absence of any write method here is
 * deliberate: entries exist only because a vendor said so, applied from
 * {@code supplier.pricecatalog.updated} events, and there is no path by which a human or another
 * service invents one.
 *
 * Issue: CAP-318 #1308
 */
public interface SupplierPriceEntryService {

    /**
     * The applicable vendor price for a product in a market as at a day (ADR-0053 §2): greatest
     * {@code effectiveFrom} not after the day, ties broken by source-document date then fetch
     * timestamp.
     *
     * @param productId       product to price
     * @param vendorProfileId restrict to one vendor; null considers every vendor's entries
     * @param countryCode     market of interest
     * @param currency        currency of interest
     * @param asOf            day to evaluate; null means today
     * @return the applicable entry, or empty when no vendor price applies on that day
     */
    @NonNull
    Optional<SupplierPriceEntryDto> findLatest(
            @NonNull UUID productId,
            @Nullable UUID vendorProfileId,
            @NonNull String countryCode,
            @NonNull String currency,
            @Nullable LocalDate asOf);

    /**
     * Cost history for a product, newest effective date first, superseded entries included.
     *
     * @param productId       product to report
     * @param vendorProfileId restrict to one vendor; null returns every vendor's entries
     * @param pageable        paging
     * @return a page of entries
     */
    @NonNull
    Page<SupplierPriceEntryDto> findHistory(
            @NonNull UUID productId, @Nullable UUID vendorProfileId, @NonNull Pageable pageable);

    /**
     * Imports this module has applied but cannot confirm complete — the producer declared more
     * chunks than arrived (ADR-0053 §7, ADR-0044 §4). A re-emit has already been requested for
     * each; this exposes them so an operator can see a feed that is not self-healing.
     *
     * @return the incomplete imports, newest first
     */
    @NonNull
    List<SupplierPriceImportStatusDto> findIncompleteImports();
}
