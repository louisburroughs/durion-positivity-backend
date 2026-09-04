package com.positivity.supplier.internal.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One purchasable-product price row from a vendor PRICAT fetch (EDIWheel Price Catalog B4.0).
 * Feeds the {@code supplier.pricecatalog.updated} fact consumed by pos-price/pos-catalog
 * (ADR-0049 §3) — sell-price policy stays in pos-price; this is vendor data verbatim.
 *
 * <p>Price fields are wrappers on purpose: null means "not stated in the catalog", never
 * zero. Pragmatic/minimal for the CAP-317 foundation slice; grows with the PRICAT capability
 * (durion#371 precedence ADR pending).
 *
 * @param articleEan EAN/GTIN identity, when stated
 * @param supplierArticleCode vendor's own article code, when stated
 * @param xReferenceCode vendor cross-reference code, when stated
 * @param suggestedRetailPrice vendor-suggested retail price, verbatim
 * @param grossPrice gross (list) purchase price, verbatim
 * @param netPrice net purchase price, verbatim
 * @param effectiveFrom first day the row applies
 * @param countryCode ISO 3166-1 alpha-2 market scope of the row
 * @param currency ISO 4217 currency of the price fields
 * @param taxRate tax rate the vendor stated for the row, when stated (ADR-0053 §2)
 * @param recyclingFee recycling fee amount the vendor stated, when stated
 * @param sourceDocumentId vendor catalog document id the row came from, when stated
 * @param sourceDocumentDate vendor catalog document date, when stated
 * @param positionNumber 1-based line position within the vendor document, when stated; lets a
 *     staged row or a quarantined line be cited back to the vendor by document position
 */
public record SupplierPriceCatalogEntry(
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        @Nullable String xReferenceCode,
        @Nullable BigDecimal suggestedRetailPrice,
        @Nullable BigDecimal grossPrice,
        @Nullable BigDecimal netPrice,
        @Nullable BigDecimal taxRate,
        @Nullable BigDecimal recyclingFee,
        @NonNull LocalDate effectiveFrom,
        @NonNull String countryCode,
        @NonNull String currency,
        @Nullable String sourceDocumentId,
        @Nullable LocalDate sourceDocumentDate,
        @Nullable Integer positionNumber) {

    // Left as IllegalArgumentException (#1694): constructed only from decoded vendor PRICAT wire
    // data (codec.toEntry, whose caller already catches IllegalArgumentException and rejects the
    // line — see EdiwheelB40PricatCodec) or from an already-valid quarantine row being replayed,
    // never from client input. A violation here is this module's own defect and belongs on the
    // platform 500 fallback, not a client 4xx.
    public SupplierPriceCatalogEntry {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(countryCode, "countryCode must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (countryCode.isBlank()) {
            throw new IllegalArgumentException("countryCode must not be blank");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if ((articleEan == null || articleEan.isBlank())
                && (supplierArticleCode == null || supplierArticleCode.isBlank())
                && (xReferenceCode == null || xReferenceCode.isBlank())) {
            throw new IllegalArgumentException(
                    "entry requires at least one product identity (articleEan, supplierArticleCode or xReferenceCode)");
        }
    }
}
