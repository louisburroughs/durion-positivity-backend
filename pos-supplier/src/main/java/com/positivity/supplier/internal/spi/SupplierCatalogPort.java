package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: manufacturer marketing catalogue
 * ({@code MARKETING_CATALOG}; EDIWheel MKCAT C1.2 JSON).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds {@code supplier.catalog.updated} for pos-catalog to attach;
 * product identity and structure stay with pos-catalog and a supplier fact must never redefine
 * them).
 *
 * <h2>Designs, not articles</h2>
 *
 * A tread design variant spans many sizes, each with its own EAN, and the catalogue carries none of
 * them. Nothing returned here can be matched to a product by article code — the identifiers are
 * brand, design names and the catalogue's own id. That limitation belongs in the port's
 * documentation because it shapes every consumer downstream of it.
 */
public interface SupplierCatalogPort {

    /** Lists every tread design variant the catalogue currently publishes. */
    @NonNull
    List<String> listVariantIds(@NonNull SupplierRef supplierRef);

    /**
     * Fetches one variant, assembled from its detail, artwork listing and language-dependent data.
     *
     * <p>Returns the artwork as the URIs the catalogue published. Fetching those bytes and storing
     * them is the caller's business: it is I/O against arbitrary asset hosts rather than against the
     * configured vendor endpoint, and doing it here would send vendor credentials to whatever host
     * the catalogue happened to name.
     */
    @NonNull
    MarketingVariant fetchVariant(@NonNull SupplierRef supplierRef, @NonNull String vendorVariantId);
}
