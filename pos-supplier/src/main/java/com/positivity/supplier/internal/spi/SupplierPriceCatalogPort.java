package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: purchasable products and prices
 * ({@code PRICE_CATALOG}; EDIWheel PRICAT B4.0).
 *
 * <p>Governing ADRs: ADR-0049 §3, ADR-0053 (vendor prices are effective-dated and never override
 * service-provider or location-specific prices).
 *
 * <p>Returns the catalogue as the vendor published it, <em>including the lines it could not read</em>.
 * A bare list of entries would be lossy in the one direction that matters: a line dropped silently
 * is a product that quietly has no vendor price, and nothing downstream could tell that from a
 * vendor that never listed it. Matching entries to products and quarantining the rest is the
 * caller's — a port that matched would be deciding catalogue questions inside the protocol layer.
 */
public interface SupplierPriceCatalogPort {

    /**
     * Fetches the vendor's current price catalogue.
     *
     * @param supplierRef the vendor profile alias to read
     * @return what the vendor published, entries and unreadable lines alike
     */
    @NonNull
    Fetched fetchPriceCatalog(@NonNull SupplierRef supplierRef);

    /**
     * A fetched catalogue, including what could not be read.
     *
     * @param entries        lines decoded into canonical entries
     * @param rejectedLines  lines the codec could not read, with the reason. Never discarded: a
     *                       silently dropped line is a product that quietly has no vendor price
     * @param linesFetched   how many lines the vendor sent, decoded or not
     */
    record Fetched(
            @NonNull List<SupplierPriceCatalogEntry> entries,
            @NonNull List<RejectedLine> rejectedLines,
            int linesFetched) {}

    /** One line the codec could not turn into an entry, and why. */
    record RejectedLine(
            @org.jspecify.annotations.Nullable Integer positionNumber,
            @org.jspecify.annotations.Nullable String articleEan,
            @org.jspecify.annotations.Nullable String supplierArticleCode,
            @org.jspecify.annotations.Nullable String xReferenceCode,
            @NonNull String detail) {}
}
