package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Typed answer to a live stock inquiry (ADR-0049 §4). This is the degradation contract of the
 * approved synchronous read: vendor-side failure never surfaces as an exception, always as a
 * non-{@link Status#OK} status the caller can render around.
 *
 * <p>Numeric fields are wrapper types on purpose: <strong>null means "not stated by the vendor",
 * never zero</strong> — a vendor that did not answer availability must not be mistaken for a vendor
 * with no stock.
 *
 * <h2>Two levels of answer</h2>
 *
 * {@link #status} says whether we got an answer at all. {@link Line#status()} says what the vendor
 * said about each article, because an inquiry naming three articles is routinely answered three
 * different ways and a single status cannot carry that.
 *
 * @param inquiryId the caller-minted inquiry correlation identity, echoed back
 * @param status document-level outcome; non-{@link Status#OK} statuses carry no vendor data
 * @param lines per-article availability; empty unless status is {@link Status#OK}
 * @param asOf when this answer was obtained; present when status is {@link Status#OK}
 */
@Schema(
        name = "StockInquiryResponse",
        description = "Live vendor availability for the inquired articles. Vendor-side failure is reported as a status,"
                + " never as an error: callers render without live stock rather than failing their own flow."
                + " A null quantity means the vendor stated none, which is not the same as a quantity of"
                + " zero.")
public record StockInquiryResponse(
        @Schema(description = "The inquiry identity supplied by the caller, echoed back.") @NonNull
        UUID inquiryId,

        @Schema(description = "Whether the vendor answered, and if not, why not.") @NonNull
        Status status,

        @Schema(description = "Per-article answers; empty unless the status is OK.") @NonNull
        List<Line> lines,

        @Schema(
                description = "When this answer was obtained. Present when the status is OK; a cached answer"
                        + " carries the instant of the original inquiry, not of the cache hit.")
        @Nullable
        Instant asOf) {

    /** Typed inquiry outcomes; the non-OK cases map ADR-0050 §3/§5 without leaking errors. */
    @Schema(name = "StockInquiryStatus", description = "Document-level outcome of a live stock inquiry.")
    public enum Status {
        /** The vendor answered the inquiry. Per-article outcomes are on the lines. */
        OK,
        /** The vendor endpoint could not be reached, failed, or its circuit breaker is open. */
        SUPPLIER_UNAVAILABLE,
        /** The vendor lists none of the inquired articles. */
        NOT_LISTED,
        /** No binding exists for this capability on the vendor profile (ADR-0050 §3). */
        CAPABILITY_NOT_CONFIGURED,
        /** The vendor profile configuration is invalid (e.g. missing delivery mapping, ADR-0050 §5). */
        CONFIGURATION_ERROR
    }

    /** What the vendor said about one article. */
    @Schema(
            name = "StockInquiryLineStatus",
            description = "What the vendor said about one article. UNAVAILABLE is the vendor stating it has none;"
                    + " NOT_ANSWERED is the vendor stating nothing. Only the first justifies showing a"
                    + " customer that the article is out of stock.")
    public enum LineStatus {
        /** The vendor stated an availability for this article. */
        AVAILABLE,
        /** The vendor stated that it has none — an answer, not a silence. */
        UNAVAILABLE,
        /** The vendor does not know, or will not sell, this article. Retrying cannot change it. */
        NOT_LISTED,
        /** The vendor returned the article without stating an availability. */
        NOT_ANSWERED
    }

    /**
     * Per-article availability answer.
     *
     * @param articleEan EAN/GTIN of the article, when stated
     * @param supplierArticleCode vendor's own article code, when stated
     * @param status what the vendor said about this article
     * @param availableQuantity available quantity; {@code null} when the vendor did not state one
     *     (never coerced to zero), {@code 0} only when {@link LineStatus#UNAVAILABLE}
     * @param earliestDeliveryDate earliest date the vendor promised any quantity, when stated
     * @param quotedUnitPrice quoted unit price, when the norm carries one. The A2.5 stock inquiry
     *     carries no price, so on that norm this is always null and a supplier price is read from
     *     the supplier price entries that own it
     * @param currency ISO 4217 currency of {@code quotedUnitPrice}, when quoted
     */
    @Schema(name = "StockInquiryLine", description = "The vendor's answer about one inquired article.")
    public record Line(
            @Schema(description = "EAN/GTIN of the article, as the vendor stated it.") @Nullable
            String articleEan,

            @Schema(description = "The vendor's own article code, as the vendor stated it.") @Nullable
            String supplierArticleCode,

            @Schema(description = "What the vendor said about this article.") @NonNull
            LineStatus status,

            @Schema(
                    description = "Quantity the vendor can supply. Null when the vendor stated none; zero only"
                            + " when the vendor said it has none.")
            @Nullable
            Integer availableQuantity,

            @Schema(description = "Earliest date the vendor promised any quantity, when stated.") @Nullable
            LocalDate earliestDeliveryDate,

            @Schema(description = "Quoted unit price, when the vendor norm carries one.") @Nullable
            BigDecimal quotedUnitPrice,

            @Schema(description = "ISO 4217 currency of the quoted price, when quoted.") @Nullable
            String currency) {

        public Line {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    public StockInquiryResponse {
        Objects.requireNonNull(inquiryId, "inquiryId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
