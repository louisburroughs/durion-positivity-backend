package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ReceivablePayment - tracks cleared payments available for application to AR
 * invoices.
 * 
 * Lifecycle:
 * - Created when PaymentCleared event received from Payment domain
 * - AVAILABLE status when unappliedAmount > 0
 * - FULLY_APPLIED when unappliedAmount = 0
 * 
 * Business Rules:
 * - Payment domain is SoR for payment lifecycle (auth/capture/settlement)
 * - Accounting domain is SoR for payment APPLICATION lifecycle
 * - Applications reduce unappliedAmount; reversals increase it
 * - Overpayments create CustomerCredit and set unappliedAmount to 0
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114 - Decision Record</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "receivable_payment", indexes = {
        @Index(name = "idx_receivable_payment_customer", columnList = "customer_id"),
        @Index(name = "idx_receivable_payment_status", columnList = "status"),
        @Index(name = "idx_receivable_payment_cleared_at", columnList = "cleared_at"),
        @Index(name = "idx_receivable_payment_source_event", columnList = "source_event_id")
})
public class ReceivablePayment {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId; // From Payment domain

    @PrePersist
    public void onPrePersist() {
        if (paymentId == null) {
            paymentId = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = ReceivablePaymentStatus.AVAILABLE;
        }
    }

    @Column(name = "customer_id", nullable = false, columnDefinition = "UUID")
    private UUID customerId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "unapplied_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal unappliedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReceivablePaymentStatus status;

    @Column(name = "cleared_at", nullable = false)
    private Instant clearedAt;

    @Column(name = "source_event_id", columnDefinition = "UUID")
    private UUID sourceEventId; // PaymentCleared event ID (idempotency)

    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;

    @Column(name = "modified_at")
    private Instant modifiedAt;

    @Column(name = "modified_by", length = 50)
    private String modifiedBy;

    @PreUpdate
    protected void onUpdate() {
        this.modifiedAt = Instant.now();
    }

    /**
     * Check if this payment has sufficient funds for an application.
     * 
     * @param amount amount to check
     * @return true if unappliedAmount >= amount
     */
    @Transient
    public boolean hasSufficientFunds(@NonNull BigDecimal amount) {
        return unappliedAmount.compareTo(amount) >= 0;
    }

    /**
     * Apply an amount to this payment (reduces unapplied balance).
     * Does not persist - caller must save.
     * 
     * @param amount amount to apply
     * @throws IllegalArgumentException if insufficient funds
     */
    public void applyAmount(@NonNull BigDecimal amount) {
        if (!hasSufficientFunds(amount)) {
            throw new IllegalArgumentException(
                    "Insufficient funds: requested " + amount + ", available " + unappliedAmount);
        }
        this.unappliedAmount = this.unappliedAmount.subtract(amount);
        if (this.unappliedAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.status = ReceivablePaymentStatus.FULLY_APPLIED;
        }
    }

    /**
     * Reverse a previously applied amount (increases unapplied balance).
     * Does not persist - caller must save.
     * 
     * @param amount amount to reverse
     */
    public void reverseAmount(@NonNull BigDecimal amount) {
        this.unappliedAmount = this.unappliedAmount.add(amount);
        this.status = ReceivablePaymentStatus.AVAILABLE;
    }

    /**
     * Payment status enum for AR payments.
     */
    public enum ReceivablePaymentStatus {
        /** Payment has unapplied amount available for application */
        AVAILABLE,
        /** Payment has been fully applied to invoices (unappliedAmount = 0) */
        FULLY_APPLIED
    }
}
