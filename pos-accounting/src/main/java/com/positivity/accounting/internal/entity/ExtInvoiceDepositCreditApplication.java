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
 * Read-only replica of a pos-invoice deposit / down-payment credit draw-down (ADR-0044 R3, issue
 * #1621), materialized from {@code payment.events.v1}'s {@code payment.deposit-credit.applied}
 * fact. Feeds the collections-analytics {@code nonCashSettled} figure.
 *
 * <p>The parent {@code DepositCredit} is deliberately not replicated — a windowed draw-down sum
 * needs only these application facts. {@link #applicationId} is generated locally (UUID v7) by
 * {@code SettlementEventsListener#onDepositCreditApplied}: the event carries no application id,
 * only the {@code (depositCreditId, invoiceId)} pair pos-invoice's {@code
 * applyAvailableCredits()} applies at most once.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_invoice_deposit_credit_application")
public class ExtInvoiceDepositCreditApplication {

    @Id
    @Column(name = "application_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "deposit_credit_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID depositCreditId;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "amount_applied", precision = 19, scale = 4, nullable = false)
    private BigDecimal amountApplied;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "source_event_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID sourceEventId;
}
