package com.positivity.supplier.internal.adapter.ediwheelb;

import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Canonical result of decoding one PRICAT document: the header facts the import manifest records,
 * the lines that decoded cleanly, and the lines that did not.
 *
 * <p>Rejected lines are part of the result rather than an exception, because a single unusable line
 * must not discard a catalog of fifty thousand good ones — and it must not vanish either. The
 * caller quarantines them (ADR-0053 §5), which is what makes "zero silent drops" checkable against
 * {@code linesFetched}.
 *
 * @param documentId    vendor document id from the envelope header, when stated
 * @param documentDate  vendor document date, when stated and parseable
 * @param countryCode   market of the document, when stated
 * @param currency      currency of every amount in the document, when stated
 * @param entries       lines that decoded into canonical entries
 * @param rejectedLines lines that could not be decoded, with the reason detail
 * @param linesFetched  how many article lines the document contained, before any filtering
 */
public record PricatDocument(
        @Nullable String documentId,
        @Nullable LocalDate documentDate,
        @Nullable String countryCode,
        @Nullable String currency,
        @NonNull List<SupplierPriceCatalogEntry> entries,
        @NonNull List<RejectedLine> rejectedLines,
        int linesFetched) {

    public PricatDocument {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(rejectedLines, "rejectedLines must not be null");
        entries = List.copyOf(entries);
        rejectedLines = List.copyOf(rejectedLines);
    }

    /**
     * One line the codec could not turn into a canonical entry.
     *
     * @param positionNumber      vendor line position, when stated
     * @param articleEan          EAN the line carried, when stated
     * @param supplierArticleCode vendor article code the line carried, when stated
     * @param xReferenceCode      cross-reference code the line carried, when stated
     * @param detail              operator-facing explanation; never a whole payload
     */
    public record RejectedLine(
            @Nullable Integer positionNumber,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable String xReferenceCode,
            @NonNull String detail) {

        public RejectedLine {
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }
}
