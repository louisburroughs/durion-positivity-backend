package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import lombok.*;

import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.shared.id.UUIDv7Generator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Credit Memo entity for reversing invoice charges.
 * 
 * Credit Memos reduce Accounts Receivable by posting offsetting GL entries
 * that reverse revenue and tax, typically for returned goods, pricing errors,
 * or service credits.
 * 
 * Business Rules (from Issue #131):
 * - Must reference a finalized invoice
 * - Credit amount cannot exceed invoice outstanding balance
 * - Requires reason code for audit
 * - GL entries must be balanced
 * - Prior period adjustments posted to current period with flag
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "credit_memo", indexes = {
        @Index(name = "idx_credit_memo_original_invoice", columnList = "original_invoice_id"),
        @Index(name = "idx_credit_memo_customer", columnList = "customer_id"),
        @Index(name = "idx_credit_memo_status", columnList = "status"),
        @Index(name = "idx_credit_memo_posted_timestamp", columnList = "posted_timestamp")
})
public class CreditMemo {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "credit_memo_id", nullable = false, updatable = false)
    private UUID creditMemoId;

    @Column(name = "original_invoice_id", nullable = false, updatable = false)
    private UUID originalInvoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditAmount;

    @Column(name = "tax_amount_reversed", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmountReversed;

    @Column(name = "reason_code", nullable = false, length = 50)
    private String reasonCode;

    @Column(name = "justification_note", length = 1000)
    private String justificationNote;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CreditMemoStatus status;

    @Column(name = "creation_timestamp", nullable = false, updatable = false)
    private Instant creationTimestamp;

    @Column(name = "posted_timestamp")
    private Instant postedTimestamp;

    @Column(name = "created_by_user_id", nullable = false, length = 50)
    private String createdByUserId;

    @Column(name = "prior_period_adjustment", nullable = false)
    private Boolean priorPeriodAdjustment = false;

    @Column(name = "original_period_id", length = 50)
    private String originalPeriodId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @PrePersist
    protected void onCreate() {
        if (creditMemoId == null) {
            creditMemoId = UUIDv7Generator.generate();
        }
        if (creationTimestamp == null) {
            creationTimestamp = Instant.now();
        }
        if (status == CreditMemoStatus.POSTED && postedTimestamp == null) {
            postedTimestamp = Instant.now();
        }
    }

    /**
     * Returns the total amount of the credit memo (credit amount + tax amount reversed).
     * This is a derived field computed from creditAmount and taxAmountReversed.
     * 
     * @return the total credit memo amount
     */
    public BigDecimal getTotalAmount() {
        BigDecimal credit = (creditAmount != null) ? creditAmount : BigDecimal.ZERO;
        BigDecimal tax = (taxAmountReversed != null) ? taxAmountReversed : BigDecimal.ZERO;
        return credit.add(tax);
    }
}
