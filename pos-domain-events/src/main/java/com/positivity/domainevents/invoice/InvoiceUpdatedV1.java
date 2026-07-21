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
        @Nullable List<TaxBreakdownLine> taxBreakdown) {

    public static final String EVENT_TYPE = "invoice.invoice.updated";
    public static final int SCHEMA_VERSION = 1;
}
