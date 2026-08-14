package com.positivity.domainevents.supplier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One article's reported availability inside a {@link SupplierStockReportUpdatedV1} chunk.
 *
 * <p>{@code availableQuantity} is nullable and that distinction is the contract: null means the
 * vendor reported the article without stating a quantity, zero means the vendor explicitly said it
 * has none. A consumer that collapses the two turns a silence into an out-of-stock.
 *
 * <p>Lines are not resolved to catalog products. Unlike PRICAT, a stock report is an availability
 * hint rather than a commercial commitment, so matching is the consumer's decision — pos-inventory
 * stores hints against the identifiers it can resolve and ignores the rest.
 *
 * @param articleEan          EAN/GTIN the vendor stated, when present
 * @param supplierArticleCode vendor's own article code — an alias, never an identifier
 * @param buyersArticleId     the buyer's own code as the vendor holds it, when present
 * @param description         vendor article description, when present
 * @param availableQuantity   quantity reported; null means none stated, which is not zero
 */
public record SupplierStockReportLine(
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        @Nullable String buyersArticleId,
        @Nullable String description,
        @Nullable Integer availableQuantity) {

    /** Marker for the absent-quantity case, so consumers do not invent their own sentinel. */
    public boolean quantityStated() {
        return availableQuantity != null;
    }

    /** Whether the vendor explicitly reported no stock, as opposed to stating nothing. */
    public boolean explicitlyOutOfStock() {
        return availableQuantity != null && availableQuantity == 0;
    }

    /** Best available identity for logging; never used as a match key by the producer. */
    @NonNull
    public String describeIdentity() {
        if (articleEan != null) {
            return "ean:" + articleEan;
        }
        if (buyersArticleId != null) {
            return "buyerArticle:" + buyersArticleId;
        }
        return "supplier:" + supplierArticleCode;
    }
}
