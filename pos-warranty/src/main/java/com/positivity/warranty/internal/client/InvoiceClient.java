package com.positivity.warranty.internal.client;

import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Client for pos-invoice: invoice-line search, invoice lookup, and settlement
 * writes (warranty adjustment / refund).
 *
 * <p>Reads degrade gracefully (empty {@code Optional}/list on 404 or transport
 * failure). Writes ({@link #createAdjustment} / {@link #createRefund}) throw
 * {@link WarrantyIntegrationException} on failure so settlements fail loudly.
 */
public interface InvoiceClient {

    /**
     * Search invoice line items by customer party id
     * ({@code GET /v1/invoices/items/search?partyId=}).
     */
    @NonNull
    List<InvoiceLine> searchInvoiceLines(@NonNull UUID partyId);

    /**
     * Fetch an invoice with its line items ({@code GET /v1/invoices/{invoiceId}}).
     */
    @NonNull
    Optional<InvoiceSummary> getInvoice(@NonNull UUID invoiceId);

    /**
     * Apply a WARRANTY-type adjustment to a draft invoice
     * ({@code POST /v1/invoices/{invoiceId}/adjustments}).
     *
     * @param externalReference warranty claim correlation reference (max 64 chars)
     * @return the created adjustment id, or empty if the response did not expose it
     * @throws WarrantyIntegrationException on HTTP error or transport failure
     */
    @NonNull
    Optional<UUID> createAdjustment(
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amount,
            @NonNull String reason,
            @NonNull String authorizedBy,
            String externalReference);

    /**
     * Refund a captured payment on an invoice, reason {@code OTHER}
     * ({@code POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds}).
     *
     * @param externalReference warranty claim correlation reference (max 64 chars)
     * @return the created refund id, or empty if the response did not expose it
     * @throws WarrantyIntegrationException on HTTP error, transport failure, or when
     *     pos-invoice reports a non-COMPLETED refund status (gateway declined — the
     *     customer was not refunded)
     */
    @NonNull
    Optional<UUID> createRefund(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentId,
            @NonNull BigDecimal amount,
            String notes,
            String externalReference);

    /** Mirrors pos-invoice {@code InvoiceLineSearchResult}. */
    record InvoiceLine(
            UUID invoiceId,
            String invoiceNumber,
            UUID invoiceItemId,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            UUID workorderItemId,
            String itemType,
            String invoiceStatus,
            Instant invoiceCreatedAt) {}

    /** Condensed view of pos-invoice {@code InvoiceDetailsResponse}. */
    record InvoiceSummary(
            UUID invoiceId,
            String invoiceNumber,
            String partyId,
            String status,
            BigDecimal total,
            List<InvoiceItem> items) {}

    /** One invoice line item within {@link InvoiceSummary}. */
    record InvoiceItem(
            UUID invoiceItemId,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            UUID workorderItemId) {}
}
