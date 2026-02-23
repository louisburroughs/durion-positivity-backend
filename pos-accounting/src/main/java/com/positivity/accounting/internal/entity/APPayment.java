package com.positivity.accounting.internal.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.shared.id.UUIDv7Id;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AP Payment entity - manages payment workflow for vendor bills.
 * 
 * Lifecycle: INITIATED → GATEWAY_PENDING → GATEWAY_SUCCEEDED → GL_POST_PENDING
 * → GL_POSTED
 * 
 * Two-phase commit semantics:
 * - Gateway success = payment succeeded (bills allocated)
 * - GL posted = accounting completion (journal entry created)
 * 
 * Idempotency: paymentRef serves as unique idempotency key.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - AP Payment</a>
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ap_payment", indexes = {
        @Index(name = "idx_ap_payment_vendor_bill", columnList = "vendor_bill_id"),
        @Index(name = "idx_ap_payment_vendor", columnList = "vendor_id"),
        @Index(name = "idx_ap_payment_status", columnList = "status"),
        @Index(name = "idx_ap_payment_date", columnList = "payment_date")
})
public class APPayment {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId;

    /** Idempotency key for duplicate prevention */
    @Column(name = "payment_ref", length = 100, unique = true, nullable = false)
    private String paymentRef;

    @Column(name = "vendor_bill_id")
    private UUID vendorBillId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "gross_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "unapplied_amount", precision = 19, scale = 4)
    private BigDecimal unappliedAmount;

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

    @Column(name = "gateway_transaction_id", length = 200)
    private String gatewayTransactionId;

    @Column(name = "gateway_response", length = 2000)
    private String gatewayResponse;

    @Column(name = "gateway_timestamp")
    private Instant gatewayTimestamp;

    @Column(name = "gl_journal_entry_id")
    private UUID glJournalEntryId;

    @Column(name = "gl_posted_at")
    private Instant glPostedAt;

    @Column(name = "gl_post_error", length = 2000)
    private String glPostError;

    @Column(name = "memo", length = 1000)
    private String memo;

    // Audit fields
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    public APPayment(UUID paymentId) {
        this.paymentId = paymentId;
    }

    @PrePersist
    public void onPrePersist() {
        if (status == null) {
            status = APPaymentStatus.INITIATED;
        }
        if (currency == null) {
            currency = "USD";
        }
    }

}
