package com.positivity.supplier.internal.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One vendor AR transaction fetched over the invoice service (EDIWheel Invoice B3.3). Feeds
 * the {@code supplier.invoice.received} fact consumed by pos-accounting (ADR-0049 §3); AP
 * voucher posting and amount normalization are the accounting consumer's concern —
 * <strong>amounts and currency here are verbatim from the vendor document</strong>, wrapper
 * types with null meaning "not stated", never zero. Consumers deduplicate by the natural
 * vendor invoice identity (ADR-0052 §5).
 *
 * <p>Pragmatic/minimal for the CAP-317 foundation slice; grows with the invoice capability.
 *
 * @param vendorInvoiceNumber vendor-native invoice identity, carried as an attribute; never blank
 * @param type document type
 * @param invoiceDate vendor document date
 * @param currency ISO 4217 currency of the amount fields; never blank
 * @param totalNetAmount total net amount, verbatim
 * @param totalTaxAmount total tax amount, verbatim
 * @param totalGrossAmount total gross amount, verbatim
 * @param lines invoice lines, verbatim
 */
public record SupplierInvoice(
        @NonNull String vendorInvoiceNumber,
        @NonNull Type type,
        @NonNull LocalDate invoiceDate,
        @NonNull String currency,
        @Nullable BigDecimal totalNetAmount,
        @Nullable BigDecimal totalTaxAmount,
        @Nullable BigDecimal totalGrossAmount,
        @NonNull List<Line> lines) {

    public enum Type {
        INVOICE,
        CREDIT_NOTE
    }

    /**
     * One invoice line, verbatim from the vendor document.
     *
     * @param lineNumber 1-based line number; {@code >= 1}
     * @param articleEan EAN/GTIN of the article, when stated
     * @param supplierArticleCode vendor's own article code, when stated
     * @param quantity invoiced quantity; {@code null} when not stated
     * @param unitPrice unit price, verbatim
     * @param lineAmount line amount, verbatim
     * @param vendorOrderReference vendor order number the line settles, when stated
     */
    public record Line(
            int lineNumber,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable Integer quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineAmount,
            @Nullable String vendorOrderReference) {

        // Left as IllegalArgumentException (#1694): built server-side from decoded vendor B3.3
        // invoice wire data, never from client input. A violation here is this module's own
        // defect and belongs on the platform 500 fallback, not a client 4xx.
        public Line {
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be >= 1");
            }
        }
    }

    // See the Line compact constructor above: same reasoning applies here.
    public SupplierInvoice {
        Objects.requireNonNull(vendorInvoiceNumber, "vendorInvoiceNumber must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(invoiceDate, "invoiceDate must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (vendorInvoiceNumber.isBlank()) {
            throw new IllegalArgumentException("vendorInvoiceNumber must not be blank");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        lines = List.copyOf(lines);
    }
}
