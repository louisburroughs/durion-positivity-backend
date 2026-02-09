package com.positivity.accounting.internal.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AP Payment entity - manages payment workflow for vendor bills.
 * 
 * Lifecycle: PENDING_APPROVAL → APPROVED → SCHEDULED → PAID (or CANCELLED)
 * 
 * Approval threshold logic:
 * - amount < threshold: requires accounting:ap_payment:approve_under_threshold
 * - amount >= threshold: requires accounting:ap_payment:approve_over_threshold
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - AP Payment</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "ap_payment", indexes = {
        @Index(name = "idx_ap_payment_vendor_bill", columnList = "vendor_bill_id"),
        @Index(name = "idx_ap_payment_vendor", columnList = "vendor_id"),
        @Index(name = "idx_ap_payment_status", columnList = "status"),
        @Index(name = "idx_ap_payment_date", columnList = "payment_date")
})
public class APPayment {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @PrePersist
    public void onPrePersist() {
        if (paymentId == null) {
            paymentId = UUIDv7Generator.generate();
        }
        createdAt = Instant.now();
        if (status == null) {
            status = APPaymentStatus.PENDING_APPROVAL;
        }
        if (currency == null) {
            currency = "USD";
        }
    }

    @Column(name = "vendor_bill_id", nullable = false)
    private UUID vendorBillId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private APPaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_date")
    private LocalDateTime paymentDate;

    @Column(name = "bank_account_id")
    private UUID bankAccountId;

    @Column(name = "approval_level", length = 30)
    private String approvalLevel;

    @Column(name = "approval_threshold", precision = 19, scale = 4)
    private BigDecimal approvalThreshold;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approval_justification", length = 1000)
    private String approvalJustification;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "scheduled_by", length = 50)
    private String scheduledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(name = "payment_transaction_id")
    private UUID paymentTransactionId;

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    public APPayment(UUID paymentId) {
        this.paymentId = paymentId;
    }

}
