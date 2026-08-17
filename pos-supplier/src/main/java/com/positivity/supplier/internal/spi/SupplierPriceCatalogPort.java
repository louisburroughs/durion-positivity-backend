package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import java.util.List;
import java.util.UUID;
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
 *
 * <h2>The manifest id, which should not be here</h2>
 *
 * {@code SupplierPriceCatalogEntry.importManifestId} is {@code @NonNull}, so an entry cannot be
 * constructed without one — which forces this port to accept a piece of <em>this platform's import
 * bookkeeping</em> in order to return vendor data. That is backwards: the canonical model of what a
 * vendor published should not carry the identity of the run that fetched it.
 *
 * <p>It is a parameter rather than something invented here because inventing one would produce
 * entries stamped with a run that does not exist. The model is the thing to fix, and it is recorded
 * as such rather than worked around silently (#1349).
 */
public interface SupplierPriceCatalogPort {

    /**
     * Fetches the vendor's current price catalogue.
     *
     * @param supplierRef      the vendor profile alias to read
     * @param importManifestId the import run these entries belong to — see the note above
     * @return what the vendor published, entries and unreadable lines alike
     */
    @NonNull
    Fetched fetchPriceCatalog(@NonNull SupplierRef supplierRef, @NonNull UUID importManifestId);

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
