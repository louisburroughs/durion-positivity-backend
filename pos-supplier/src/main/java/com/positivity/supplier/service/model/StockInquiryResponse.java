package com.positivity.supplier.service.model;

import java.math.BigDecimal;
import java.time.Instant;
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
 * <p>Numeric fields are wrapper types on purpose: <strong>null means "not stated by the
 * vendor", never zero</strong> — a vendor that did not answer availability must not be mistaken
 * for a vendor with no stock.
 *
 * @param inquiryId the caller-minted inquiry correlation identity, echoed back
 * @param status typed outcome; non-{@link Status#OK} statuses carry no vendor data
 * @param lines per-article availability; empty unless status is {@link Status#OK}
 * @param asOf vendor-stated snapshot instant; present when status is {@link Status#OK}
 */
public record StockInquiryResponse(
        @NonNull UUID inquiryId, @NonNull Status status, @NonNull List<Line> lines, @Nullable Instant asOf) {

    /** Typed inquiry outcomes; the non-OK cases map ADR-0050 §3/§5 without leaking errors. */
    public enum Status {
        /** The vendor answered the inquiry. */
        OK,
        /** The vendor endpoint could not be reached, failed, or its circuit breaker is open. */
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
     * @param currency ISO 4217 currency of {@code quotedUnitPrice}, when quoted
     */
    public record Line(
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable Integer availableQuantity,
            @Nullable BigDecimal quotedUnitPrice,
            @Nullable String currency) {}

    public StockInquiryResponse {
        Objects.requireNonNull(inquiryId, "inquiryId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
