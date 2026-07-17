package com.positivity.warranty.internal.client;

import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
     * Search invoice line items by customer party id, optionally narrowed by a SKU /
     * description term applied server-side
     * ({@code GET /v1/invoices/items/search?partyId=&q=}).
     */
    @NonNull
    List<InvoiceLine> searchInvoiceLines(@NonNull UUID partyId, @Nullable String sku);

    /**
     * Fetch an invoice with its line items ({@code GET /v1/invoices/{invoiceId}}).
     */
    @NonNull
    Optional<InvoiceSummary> getInvoice(@NonNull UUID invoiceId);

    /**
     * Fetch the adjustment entries recorded on an invoice
     * ({@code GET /v1/invoices/{invoiceId}} — the details response exposes each adjustment's
     * {@code externalReference}), for settlement reconciliation.
     *
     * @return the authoritative adjustment list ({@code Optional.of(List.of())} when the
     *     invoice does not exist — nothing can be committed on a missing invoice), or
     *     {@code Optional.empty()} when pos-invoice could not be consulted (transport or
     *     non-404 HTTP failure) and the caller must treat the answer as UNKNOWN
     */
    @NonNull
    Optional<List<AdjustmentEntry>> getInvoiceAdjustments(@NonNull UUID invoiceId);

    /**
     * Fetch the refund records on an invoice ({@code GET /v1/invoices/{invoiceId}/refunds}),
     * for settlement reconciliation.
     *
     * @return the authoritative refund list ({@code Optional.of(List.of())} when the invoice
     *     does not exist), or {@code Optional.empty()} when pos-invoice could not be consulted
     *     and the caller must treat the answer as UNKNOWN
     */
    @NonNull
    Optional<List<RefundEntry>> getInvoiceRefunds(@NonNull UUID invoiceId);

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
            @Nullable String externalReference);

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
            @Nullable String notes,
            @Nullable String externalReference);

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

    /** One adjustment entry on the pos-invoice details response. */
    record AdjustmentEntry(UUID id, String type, BigDecimal amount, String externalReference, Instant createdAt) {}

    /** One refund record from {@code GET /v1/invoices/{invoiceId}/refunds}. */
    record RefundEntry(
            UUID id,
            UUID paymentIntentId,
            BigDecimal amount,
            String status,
            String reason,
            String externalReference,
            String gatewayReference,
            Instant requestedAt,
            Instant completedAt) {}
}
