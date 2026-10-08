package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The posting of an approved vendor bill (#2509; AW37-AW42), the {@code invoice_gl_posting} pattern: one row per
 * bill, written in the approval's transaction, so a bill is approved if and only if it has one. {@link #sourceKey}
 * {@code VENDOR_BILL:<billId>} and {@link #reversalSourceKey} {@code VENDOR_BILL_VOID:<billId>} are unique per
 * tenant, the durable idempotency keys of the posting and of the void's reversal (never a 24-hour key, #2595).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(
        name = "vendor_bill_gl_posting",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_vendor_bill_gl_posting_bill",
                    columnNames = {"tenant_id", "vendor_bill_id"}),
            @UniqueConstraint(
                    name = "uq_vendor_bill_gl_posting_source_key",
                    columnNames = {"tenant_id", "source_key"}),
            @UniqueConstraint(
                    name = "uq_vendor_bill_gl_posting_reversal_key",
                    columnNames = {"tenant_id", "reversal_source_key"})
        })
public class VendorBillGlPosting extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_bill_gl_posting_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorBillGlPostingId;

    @Column(name = "vendor_bill_id", nullable = false, updatable = false)
    private UUID vendorBillId;

    /** {@code VENDOR_BILL:<billId>}. */
    @Column(name = "source_key", length = 80, nullable = false, updatable = false)
    private String sourceKey;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "posting_date", nullable = false, updatable = false)
    private LocalDate postingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_date_rule", length = 40, nullable = false, updatable = false)
    private VendorBillPostingDateRule postingDateRule;

    /** The class the approver gave a bill without receipt-matched lines; null when the lines classed themselves. */
    @Enumerated(EnumType.STRING)
    @Column(name = "debit_class", length = 30, updatable = false)
    private VendorBillDebitClass debitClass;

    /** The {@code VENDOR_BILL} key {@code EXPENSE_<CODE>} expense lines posted to, when there were any. */
    @Column(name = "expense_mapping_key", length = 100, updatable = false)
    private String expenseMappingKey;

    /** The billed gross credited to accounts payable (debited for a credit note). */
    @Column(name = "gross_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal grossAmount;

    /** ISO 4217 code of {@link #grossAmount} (ADR-0067 R-1): the ledger currency (AW43). */
    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    /** The rounding plug put on the largest debit, within the tolerance (AW46); zero when the legs added up. */
    @Column(name = "rounding_adjustment", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal roundingAdjustment;

    /** How an unreconciled gross - (net + tax) posted (AW46); null when the vendor's totals added up. */
    @Enumerated(EnumType.STRING)
    @Column(name = "difference_class", length = 30, updatable = false)
    private VendorBillDifferenceClass differenceClass;

    @Column(name = "difference_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal differenceAmount;

    @Column(name = "difference_justification", length = 1000, updatable = false)
    private String differenceJustification;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "posted_by", length = 50, nullable = false, updatable = false)
    private String postedBy;

    /** {@code VENDOR_BILL_VOID:<billId>} once the approved bill is voided. */
    @Column(name = "reversal_source_key", length = 80)
    private String reversalSourceKey;

    @Column(name = "reversal_journal_entry_id")
    private UUID reversalJournalEntryId;

    /** The void date: the reversal's transaction date, never back in the original period (AW42). */
    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;
}
