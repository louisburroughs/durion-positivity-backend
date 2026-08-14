package com.positivity.supplier.internal.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One vendor stock-report snapshot: what the vendor said it had, for a whole market, at one
 * moment (EDIWheel Stock Report B2.1).
 *
 * <p>The dedicated model {@code SupplierStockReportPort} was waiting for (CAP-322). It differs
 * from a stock <em>inquiry</em> in what its silences mean: an inquiry asks about named articles
 * and answers each one, while a snapshot lists what the vendor chose to report. An article
 * missing from a snapshot is therefore <strong>no data</strong>, not zero stock — the vendor did
 * not say it has none, it did not mention it. Consumers must not turn absence into availability
 * of zero.
 *
 * @param documentId    vendor document id from the envelope header, when stated
 * @param issuedOn      vendor-stated issue date of the document
 * @param issuedAt      vendor-stated issue instant when the document carried a time; else null
 * @param buyerParty    buyer account the snapshot was produced for
 * @param lines         per-article availability the vendor reported
 * @param rejectedLines lines that could not be decoded, kept with their reason
 * @param linesFetched  how many lines the document contained before any filtering
 */
public record SupplierStockSnapshot(
        @Nullable String documentId,
        @Nullable LocalDate issuedOn,
        @Nullable Instant issuedAt,
        @Nullable String buyerParty,
        @NonNull List<Line> lines,
        @NonNull List<RejectedLine> rejectedLines,
        int linesFetched) {

    public SupplierStockSnapshot {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(rejectedLines, "rejectedLines must not be null");
        lines = List.copyOf(lines);
        rejectedLines = List.copyOf(rejectedLines);
    }

    /**
     * One article's reported availability.
     *
     * @param lineId              vendor line id, when stated
     * @param articleEan          EAN/GTIN identity, when stated
     * @param supplierArticleCode vendor's own article code — an alias, never an identifier
     * @param buyersArticleId     the buyer's own code as the vendor holds it, when stated
     * @param description         vendor article description, when stated
     * @param availableQuantity   quantity the vendor reported; null means the vendor stated none,
     *                            which is not the same as reporting zero
     */
    public record Line(
            @Nullable String lineId,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable String buyersArticleId,
            @Nullable String description,
            @Nullable Integer availableQuantity) {}

    /**
     * A line the codec could not decode.
     *
     * @param lineId vendor line id, when stated
     * @param detail operator-facing explanation; never a payload dump
     */
    public record RejectedLine(
            @Nullable String lineId, @NonNull String detail) {

        public RejectedLine {
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }
}
