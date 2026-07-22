package com.positivity.domainevents.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code invoice.invoice.updated} v1 on {@code invoice.events.v1} (ADR-0044, #842).
 *
 * <p>Published by pos-invoice after every invoice document mutation (create, adjustment,
 * finalize, revert, GL posting). Carries the document facts pos-invoice owns — lifecycle status
 * ({@code DRAFT}/{@code FINALIZED}/{@code POSTED}/{@code ERROR}) and totals. It deliberately does
 * NOT carry an AR balance: payments, credit memos, and therefore the balance due are
 * pos-accounting's facts (ADR-0044 R6), derived there from this replica plus accounting's own
 * records.
 *
 * <p>{@code partyId} is a free-form string because pos-invoice stores it that way (usually a
 * commercial-party UUID). The envelope's {@code aggregateVersion} is the invoice row's JPA
 * optimistic-lock version, so consumers can drop stale out-of-order deliveries.
 *
 * <p>{@code taxBreakdown} (story T5b) is the additive per-line × per-jurisdiction tax matrix
 * pos-invoice persists ({@code invoice_line_tax}). It stays within schema v1 (ADR-0044 §3:
 * additive, consumers tolerate unknown/absent fields) — there is no {@code InvoiceUpdatedV2} and
 * no dual-publish. It is {@code null} for producers/older events that carry no breakdown; the
 * scalar {@code tax} above remains the denormalized rollup (decision D-T6), and where the
 * breakdown is present {@code tax == Σ taxBreakdown.taxAmount} holds.
 *
 * <p>{@code lines} is the additive per-line-item detail (schema v1, #924) that lets consumers
 * build a line-grained {@code ext_invoice} replica — pos-warranty's candidate-line search reads
 * it in place of the retired synchronous {@code InvoiceClient.searchInvoiceLines}. Like
 * {@code taxBreakdown} it is authoritative-empty vs. {@code null}: an empty list means the invoice
 * genuinely has no items, {@code null} means the producer projected none (legacy/older events).
 */
public record InvoiceUpdatedV1(
        @NonNull UUID invoiceId,
        @Nullable String invoiceNumber,
        @NonNull UUID workorderId,
        @Nullable UUID estimateId,
        @Nullable UUID locationId,
        @Nullable String partyId,
        @NonNull String status,
        @Nullable BigDecimal subtotal,
        @Nullable BigDecimal tax,
        @Nullable BigDecimal total,
        @Nullable BigDecimal adjustmentsAmount,
        @Nullable Instant createdAt,
        @Nullable Instant finalizedAt,
        @Nullable List<TaxBreakdownLine> taxBreakdown,
        @Nullable List<InvoiceLine> lines) {

    public static final String EVENT_TYPE = "invoice.invoice.updated";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One invoice line item.
     *
     * @param invoiceItemId line-item identifier
     * @param workorderItemId originating workorder line (null for lines not sourced from a workorder)
     * @param itemType owner line {@code type} (e.g. {@code PART}/{@code LABOR}), null when unset
     */
    public record InvoiceLine(
            @NonNull UUID invoiceItemId,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal amount,
            @Nullable UUID workorderItemId,
            @Nullable String itemType) {}

    /** Pre-#924 arity (no line detail): older producers/tests project no {@code lines}. */
    public InvoiceUpdatedV1(
            @NonNull UUID invoiceId,
            @Nullable String invoiceNumber,
            @NonNull UUID workorderId,
            @Nullable UUID estimateId,
            @Nullable UUID locationId,
            @Nullable String partyId,
            @NonNull String status,
            @Nullable BigDecimal subtotal,
            @Nullable BigDecimal tax,
            @Nullable BigDecimal total,
            @Nullable BigDecimal adjustmentsAmount,
            @Nullable Instant createdAt,
            @Nullable Instant finalizedAt,
            @Nullable List<TaxBreakdownLine> taxBreakdown) {
        this(
                invoiceId,
                invoiceNumber,
                workorderId,
                estimateId,
                locationId,
                partyId,
                status,
                subtotal,
                tax,
                total,
                adjustmentsAmount,
                createdAt,
                finalizedAt,
                taxBreakdown,
                null);
    }
}
