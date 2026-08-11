package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: purchasable products and prices fetch (PRICAT)
 * ({@code PRICE_CATALOG}; EDIWheel Price Catalog B4.0).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds the {@code supplier.pricecatalog.updated} fact
 * consumed by pos-price and pos-catalog — sell-price policy stays with pos-price), ADR-0052
 * §5 (batch reads are idempotent by checkpointed window and may retry freely).
 */
public interface SupplierPriceCatalogPort {

    /** Fetches the vendor's current price catalog for the profile's market. */
    @NonNull
    SupplierExchange<List<SupplierPriceCatalogEntry>> fetchPriceCatalog(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext);
}
