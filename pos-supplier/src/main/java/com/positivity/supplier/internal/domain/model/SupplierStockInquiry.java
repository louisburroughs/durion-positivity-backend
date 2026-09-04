package com.positivity.supplier.internal.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Real-time stock availability inquiry request (EDIWheel Stock Inquiry / UPX; ADR-0049 §4 —
 * the platform's single synchronous cross-module supplier read).
 *
 * <p>Pragmatic/minimal for the CAP-317 foundation slice; grows with the stock-inquiry
 * capability (CAP-321), never to mirror a wire format (ADR-0051 §4).
 *
 * @param inquiryId caller-minted correlation identity for this inquiry
 * @param lines articles to inquire about; never empty
 */
public record SupplierStockInquiry(
        @NonNull UUID inquiryId, @NonNull List<Line> lines) {

    /**
     * One inquired article. At least one product identity (EAN or supplier article code) is
     * required.
     *
     * @param articleEan EAN/GTIN of the article, when known
     * @param supplierArticleCode vendor's own article code, when known
     * @param requestedQuantity quantity whose availability is being checked; {@code >= 1}
     */
    public record Line(
            @Nullable String articleEan, @Nullable String supplierArticleCode, int requestedQuantity) {

        // Left as IllegalArgumentException (#1694): this is the internal canonical twin of
        // service.model.StockInquiryRequest, built server-side by converting an already-validated
        // StockInquiryRequest/availability-controller call — the same guard at the boundary makes
        // this one unreachable in practice. A violation here would be this module's own defect and
        // belongs on the platform 500 fallback, not a client 4xx.
        public Line {
            if (requestedQuantity < 1) {
                throw new IllegalArgumentException("requestedQuantity must be >= 1");
            }
            if ((articleEan == null || articleEan.isBlank())
                    && (supplierArticleCode == null || supplierArticleCode.isBlank())) {
                throw new IllegalArgumentException(
                        "line requires at least one product identity (articleEan or supplierArticleCode)");
            }
        }
    }

    // See the Line compact constructor above: same reasoning applies here.
    public SupplierStockInquiry {
        Objects.requireNonNull(inquiryId, "inquiryId must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        lines = List.copyOf(lines);
    }
}
