package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.shared.id.UUIDv7Id;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entity representing the current payment and accounting status of an invoice.
 *
 * Stores both POS payment status (Story #6 — CAP-251) and accounting
 * authoritative status
 * for reconciliation tracking (Story #5 — CAP-251).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "invoice_status_views")
public class InvoiceStatusView {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;
    @Column(nullable = false, unique = true)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus currentStatus;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPaid;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal invoiceTotal;

    @Column(nullable = false)
    private Instant lastUpdated;

    @Column
    private String latestTransactionReference;

    @Column
    private Long totalAmountMinor;

    @Column
    private Long outstandingAmountMinor;

    @Column
    private Long paidAmountMinor;

    @Column(nullable = false)
    private boolean postingError;

    @Column
    private String latestIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_status")
    private AccountingStatus accountingStatus;

    @Column(name = "accounting_status_updated_at")
    private Instant accountingStatusUpdatedAt;

    @Column(name = "posting_reference")
    private String postingReference;

    @Column(name = "archived")
    private boolean archived;

    /**
     * True when the accounting system reported a reconciliation discrepancy for this invoice.
     * Set to {@code true} when accounting status is {@code REJECTED}; reset to {@code false}
     * on any subsequent status update to a non-rejected state.
     */
    @Column(name = "discrepancy_detected", nullable = false)
    private boolean discrepancyDetected;

    /**
     * Human-readable explanation of the discrepancy provided by the accounting system.
     * Populated when {@code discrepancyDetected} is {@code true}; null otherwise.
     */
    @Column(name = "discrepancy_reason", length = 1000)
    private String discrepancyReason;

    public InvoiceStatusView(UUID invoiceId, PaymentStatus currentStatus,
            BigDecimal totalPaid, BigDecimal invoiceTotal) {
        this.invoiceId = invoiceId;
        this.currentStatus = currentStatus;
        this.totalPaid = totalPaid;
        this.invoiceTotal = invoiceTotal;
    }

    public boolean isDiscrepancyDetected() {
        return discrepancyDetected;
    }

    public void setDiscrepancyDetected(boolean discrepancyDetected) {
        this.discrepancyDetected = discrepancyDetected;
    }

    public String getDiscrepancyReason() {
        return discrepancyReason;
    }

    public void setDiscrepancyReason(String discrepancyReason) {
        this.discrepancyReason = discrepancyReason;
    }
}
