package com.positivity.supplier.internal.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of a stock availability read.
 *
 * <p>Numeric fields are wrapper types on purpose: <strong>null means "not stated by the vendor",
 * never zero</strong> — a vendor that did not answer availability must not be mistaken for a vendor
 * with no stock.
 *
 * <h2>Why the answer is per line as well as per document</h2>
 *
 * An inquiry may name several articles and a vendor routinely answers them differently: it stocks
 * the first, has none of the second, and has never heard of the third. A single document-level
 * status cannot say that, and collapsing it would either hide that an article is unknown or declare
 * the whole inquiry unanswered because one line was. {@link #status} therefore describes the
 * document — did we get an answer at all — and {@link Line#status()} describes each article.
 *
 * @param status document-level outcome; non-{@link Status#OK} statuses carry no vendor data and map
 *     the unbound/unavailable cases of ADR-0050 §3 without leaking errors
 * @param lines per-article availability; empty unless status is {@link Status#OK}
 * @param asOf vendor-stated snapshot instant; present when status is {@link Status#OK}
 */
public record SupplierStockInquiryResult(
        @NonNull Status status,
        @NonNull List<Line> lines,
        @Nullable Instant asOf) {

    public enum Status {
        /** The vendor answered the inquiry. Per-line outcomes are on the lines. */
        OK,
        /** The vendor endpoint could not be reached or failed. */
        SUPPLIER_UNAVAILABLE,
        /** The vendor lists none of the inquired articles. */
        NOT_LISTED,
        /** No binding exists for this capability on the vendor profile (ADR-0050 §3). */
        CAPABILITY_NOT_CONFIGURED,
        /** The vendor profile configuration is invalid (e.g. missing delivery mapping, ADR-0050 §5). */
        CONFIGURATION_ERROR
    }

    /** What the vendor said about one article. */
    public enum LineStatus {
        /** The vendor stated an availability for this article. */
        AVAILABLE,
        /**
         * The vendor stated that it has none. This is an answer, not a silence: it is the one case
         * where a quantity of zero is a fact rather than an assumption.
         */
        UNAVAILABLE,
        /**
         * The vendor does not know, or will not sell, this article. Retrying an identical inquiry
         * cannot change this, which is what separates it from {@link Status#SUPPLIER_UNAVAILABLE}.
         */
        NOT_LISTED,
        /**
         * The vendor returned the line without stating an availability — it could not determine
         * one, or it rejected the line for a reason unrelated to the article's identity.
         */
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
     * @param quotedUnitPrice quoted unit price; {@code null} when the vendor did not quote. The
     *     A2.5 norm carries no price at all — only the C1.0 inquiry does — so on that norm this is
     *     always null and a supplier price is taken from the PRICAT entries that own it
     * @param currency ISO currency of {@code quotedUnitPrice}, when quoted
     */
    public record Line(
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @NonNull LineStatus status,
            @Nullable Integer availableQuantity,
            @Nullable LocalDate earliestDeliveryDate,
            @Nullable BigDecimal quotedUnitPrice,
            @Nullable String currency) {

        // Left as IllegalArgumentException (#1694): built server-side from a decoded vendor
        // availability answer, never from client input. A violation here is this module's own
        // defect and belongs on the platform 500 fallback, not a client 4xx.
        public Line {
            Objects.requireNonNull(status, "status must not be null");
            if (status != LineStatus.AVAILABLE && status != LineStatus.UNAVAILABLE && availableQuantity != null) {
                throw new IllegalArgumentException(
                        "a quantity may only accompany an answered line, but status was " + status);
            }
        }
    }

    public SupplierStockInquiryResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
