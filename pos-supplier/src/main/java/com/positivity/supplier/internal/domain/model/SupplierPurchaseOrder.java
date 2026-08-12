package com.positivity.supplier.internal.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Transmission-state view of a purchase order handed to a vendor (ADR-0049 §2, ADR-0052 §1).
 *
 * <p>pos-supplier holds <em>transmission</em> state only — the purchase-order aggregate stays
 * in pos-order. The {@code documentId} is the wire {@code DocumentID}/{@code CustomerReference}
 * derived deterministically from {@code transmissionIntentId}: never regenerated on retry of
 * the same intent, always new for a new intent (ADR-0052 §1).
 *
 * <p>Pragmatic/minimal for the CAP-317 foundation slice; line shape grows with CAP-320
 * (order transmission), never to mirror a wire format (ADR-0051 §4).
 *
 * @param transmissionIntentId immutable idempotency identity of this transmission intent
 * @param purchaseOrderId originating pos-order purchase-order aggregate id
 * @param intentType why this transmission exists relative to the purchase order
 * @param revision revision number of the purchase order this intent transmits; {@code >= 0}
 * @param documentId wire document id derived from {@code transmissionIntentId}; never blank
 * @param lines the order lines to transmit; never empty
 */
public record SupplierPurchaseOrder(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @NonNull IntentType intentType,
        int revision,
        @NonNull String documentId,
        @NonNull List<Line> lines) {

    /** Intent taxonomy per ADR-0052 §1. */
    public enum IntentType {
        INITIAL,
        REVISION,
        REPLACEMENT,
        SPLIT
    }

    /**
     * One order line. At least one product identity (EAN or supplier article code) is required.
     *
     * @param lineNumber 1-based line number; {@code >= 1}
     * @param articleEan EAN/GTIN of the article, when known
     * @param supplierArticleCode vendor's own article code, when known
     * @param quantity ordered quantity; {@code >= 1}
     */
    public record Line(
            int lineNumber,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            int quantity) {

        public Line {
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be >= 1");
            }
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be >= 1");
            }
            if (isBlank(articleEan) && isBlank(supplierArticleCode)) {
                throw new IllegalArgumentException(
                        "line requires at least one product identity (articleEan or supplierArticleCode)");
            }
        }

        private static boolean isBlank(@Nullable String value) {
            return value == null || value.isBlank();
        }
    }

    public SupplierPurchaseOrder {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(intentType, "intentType must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        lines = List.copyOf(lines);
    }
}
