package com.positivity.supplier.service.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Live stock availability inquiry request (ADR-0049 §4 — the single approved synchronous
 * cross-module supplier read; implementation arrives with CAP-319).
 *
 * <p>Stock inquiry is a delivery-bearing capability: the delivery account number for
 * {@code deliveryLocationId} is resolved from the vendor profile's delivery mapping before any
 * network call; a missing mapping is a {@code CONFIGURATION_ERROR} (ADR-0050 §5).
 *
 * @param inquiryId caller-minted correlation identity for this inquiry
 * @param vendorProfileId vendor profile to inquire against (UUIDv7 identity, ADR-0050 §1)
 * @param deliveryLocationId pos-location UUID of the receiving location
 * @param lines articles to inquire about; never empty
 */
public record StockInquiryRequest(
        @NonNull UUID inquiryId,
        @NonNull UUID vendorProfileId,
        @NonNull UUID deliveryLocationId,
        @NonNull List<Line> lines) {

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

    public StockInquiryRequest {
        Objects.requireNonNull(inquiryId, "inquiryId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(deliveryLocationId, "deliveryLocationId must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        lines = List.copyOf(lines);
    }
}
