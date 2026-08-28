package com.positivity.supplier.internal.mktcat.service;

import com.positivity.supplier.internal.mktcat.service.model.MarketingCatalogSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read surface over vendor marketing-catalog (MKCAT) fetches (ADR-0026 contract).
 *
 * <p>Marketing catalog content enriches product data in pos-catalog through events; this
 * interface exposes fetch bookkeeping for the module's own frontend-facing controllers.
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; implementation arrives with CAP-324
 * (MKCAT marketing catalog C1.2 JSON, durion#379).
 */
public interface SupplierCatalogService {

    /**
     * Returns the most recent marketing-catalog snapshot fetched for a vendor profile.
     *
     * @param vendorProfileId vendor profile identity (UUIDv7, ADR-0050 §1)
     * @return the latest snapshot summary, or empty when no fetch ever completed
     */
    @NonNull
    Optional<MarketingCatalogSnapshot> findLatestSnapshot(@NonNull UUID vendorProfileId);
}
