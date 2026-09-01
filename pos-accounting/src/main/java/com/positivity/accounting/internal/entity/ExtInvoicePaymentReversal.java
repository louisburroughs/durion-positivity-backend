package com.positivity.accounting.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only replica of a pos-invoice completed refund (ADR-0044 R3, issue #1620), materialized
 * from {@code payment.events.v1}'s {@code payment.payment.reversed} fact when {@code
 * reversalType == "REFUND"}. VOID reversals are deliberately never stored here — see {@code
 * SettlementEventsListener#onPaymentReversed}.
 *
 * <p>Immutable fact row: a reversal is never revised once recorded, so unlike {@link ExtInvoice}
 * this replica carries no {@code aggregate_version} guard — {@link #refundId} is the natural key
 * and each id is written at most once. The id is assigned by pos-invoice (the refund record id)
 * and is never generated here.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_invoice_payment_reversal")
public class ExtInvoicePaymentReversal {

    @Id
    @Column(name = "refund_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID refundId;

    @Column(name = "payment_intent_id", columnDefinition = "UUID")
    private UUID paymentIntentId;

    @Column(name = "invoice_id", columnDefinition = "UUID")
    private UUID invoiceId;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "reversal_type", length = 16, nullable = false)
    private String reversalType;

    @Column(name = "reversed_at", nullable = false)
    private Instant reversedAt;

    @Column(name = "source_event_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID sourceEventId;
}
