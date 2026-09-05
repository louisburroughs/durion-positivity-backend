package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "credit_memo",
        indexes = {
            @Index(name = "idx_credit_memo_original_invoice", columnList = "original_invoice_id"),
            @Index(name = "idx_credit_memo_customer", columnList = "customer_id"),
            @Index(name = "idx_credit_memo_status", columnList = "status"),
            @Index(name = "idx_credit_memo_posted_timestamp", columnList = "posted_timestamp"),
            @Index(name = "uq_credit_memo_reference", columnList = "credit_memo_reference", unique = true)
        })
public class CreditMemo {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "credit_memo_id", nullable = false, updatable = false)
    private UUID creditMemoId;

    /**
     * Short human-readable label ({@code CM-{YYYYMM}-{n}}, e.g. {@code CM-202609-7}) shown in
     * place of the raw {@code creditMemoId} UUID (issue #1779). Assigned at creation from the
     * per-month {@code accounting_sequence} counter, reusing the numbering machinery behind
     * {@code accounting_event.event_reference} (#1680) and {@code journal_entry.entry_number}
     * (#942). {@code creditMemoId} stays the canonical identifier (ADR-0027); this is purely an
     * additional display field, so it is nullable — assignment is a service-layer concern, not a
     * database invariant.
     */
    @Column(name = "credit_memo_reference", length = 20)
    private String creditMemoReference;

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
    private boolean priorPeriodAdjustment = false;

    @Column(name = "original_period_id", length = 50)
    private String originalPeriodId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When the memo was voided (issue #997 symmetry); null unless status is VOIDED. */
    @Column(name = "voided_timestamp")
    private Instant voidedTimestamp;

    @Column(name = "voided_by_user_id", length = 50)
    private String voidedByUserId;

    @Column(name = "void_reason", length = 1000)
    private String voidReason;

    @PrePersist
    protected void onCreate() {
        if (creationTimestamp == null) {
            creationTimestamp = TimeSource.instant();
        }
        if (status == CreditMemoStatus.POSTED && postedTimestamp == null) {
            postedTimestamp = TimeSource.instant();
        }
    }

    /**
     * Calculates the total amount of the credit memo (credit amount + tax amount reversed).
     * This is a derived field computed from creditAmount and taxAmountReversed.
     *
     * @return the total credit memo amount, or BigDecimal.ZERO if components are null
     */
    @Transient
    public BigDecimal calculateTotalAmount() {
        BigDecimal credit = (creditAmount != null) ? creditAmount : BigDecimal.ZERO;
        BigDecimal tax = (taxAmountReversed != null) ? taxAmountReversed : BigDecimal.ZERO;
        return credit.add(tax);
    }
}
