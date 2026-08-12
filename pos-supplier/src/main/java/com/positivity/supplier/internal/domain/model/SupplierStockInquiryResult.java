package com.positivity.supplier.internal.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of a stock availability read. Also reused as the line shape of the B-series stock
 * <em>report</em> snapshot until CAP-322 grows a dedicated snapshot model — both answer
 * "how much of which article does the vendor have, as of when".
 *
 * <p>Numeric fields are wrapper types on purpose: <strong>null means "not stated by the
 * vendor", never zero</strong> — a vendor that did not answer availability must not be
 * mistaken for a vendor with no stock.
 *
 * @param status typed outcome; non-{@link Status#OK} statuses carry no vendor data and map
 *     the unbound/unavailable cases of ADR-0050 §3 without leaking errors
 * @param lines per-article availability; empty unless status is {@link Status#OK}
 * @param asOf vendor-stated snapshot instant; present when status is {@link Status#OK}
 */
public record SupplierStockInquiryResult(
        @NonNull Status status,
        @NonNull List<Line> lines,
        @Nullable Instant asOf) {

    public enum Status {
        /** The vendor answered the inquiry. */
        OK,
        /** The vendor endpoint could not be reached or failed. */
        SUPPLIER_UNAVAILABLE,
        /** The vendor does not list the inquired article(s). */
        NOT_LISTED,
        /** No binding exists for this capability on the vendor profile (ADR-0050 §3). */
        CAPABILITY_NOT_CONFIGURED,
        /** The vendor profile configuration is invalid (e.g. missing delivery mapping, ADR-0050 §5). */
        CONFIGURATION_ERROR
    }

    /**
     * Per-article availability answer.
     *
     * @param articleEan EAN/GTIN of the article, when stated
     * @param supplierArticleCode vendor's own article code, when stated
     * @param availableQuantity available quantity; {@code null} when the vendor did not state
     *     one (never coerced to zero)
     * @param quotedUnitPrice quoted unit price; {@code null} when the vendor did not quote
     * @param currency ISO currency of {@code quotedUnitPrice}, when quoted
     */
    public record Line(
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable Integer availableQuantity,
            @Nullable BigDecimal quotedUnitPrice,
            @Nullable String currency) {}

    public SupplierStockInquiryResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
