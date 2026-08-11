package com.positivity.supplier.service;

import com.positivity.supplier.service.model.PriceCatalogImportSummary;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read surface over vendor price-catalog (PRICAT) imports (ADR-0026 contract).
 *
 * <p>Catalog rows themselves flow to consumers as the {@code supplier.pricecatalog.updated}
 * fact (pos-price, pos-catalog — ADR-0049 §3); this interface exposes import bookkeeping for
 * the module's own frontend-facing controllers. Sell-price policy stays in pos-price; vendor
 * prices never override service-provider prices (architecture doc §8.1, durion#371).
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; implementation arrives with CAP-318
 * (Price Catalog B4.0 sync, durion#373).
 */
public interface SupplierPriceCatalogService {

    /**
     * Returns the most recent completed price-catalog import for a vendor profile.
     *
     * @param vendorProfileId vendor profile identity (UUIDv7, ADR-0050 §1)
     * @return the latest import summary, or empty when no import ever completed
     */
    @NonNull
    Optional<PriceCatalogImportSummary> findLatestImport(@NonNull UUID vendorProfileId);
}
