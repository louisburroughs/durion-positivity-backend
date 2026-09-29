package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Bank reconciliation header (Story F2, issue #965; extended by story S1, #2300 —
 * SPEC-manual-bank-reconciliation §3.7).
 *
 * <p>One row per reconciled statement window of one reconcilable GL cash account. Single
 * currency per reconciliation (hard invariant; {@link #currency} governs). The
 * reconciliation rests on a {@code bank_statement} ({@link #statementId}) and selects that
 * statement's bank transactions; it no longer owns statement lines. {@link #glEndingBalance}
 * is snapshotted at import from posted journal-entry lines on the account as-of
 * {@link #statementEndDate} (F2's {@code statementDate} is retired: it equals the statement
 * end date). Finalizing requires the balance gate to net to zero (difference within ±0.01)
 * and flips the status to {@link ReconciliationStatus#FINALIZED}.
 *
 * <p>The columns of the explicit equation (E3), the opening terms, the unexplained counts and
 * the approval/invalidation/supersession/cancellation actors are carried here from story S1 on,
 * nullable, and are filled by stories S4–S5. {@link #version} guards concurrent edits
 * (§6.3; a stale version answers 409 {@code OPTIMISTIC_LOCK}).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"glAccount", "adjustments"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bank_reconciliation",
        indexes = {
            @Index(name = "idx_bank_reconciliation_account", columnList = "gl_account_id"),
            @Index(name = "idx_bank_reconciliation_status", columnList = "status")
        })
public class BankReconciliation extends TenantScopedEntity {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "reconciliation_id", nullable = false, columnDefinition = "UUID")
    private UUID reconciliationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gl_account_id", nullable = false)
    private GLAccount glAccount;

    @Column(name = "account_code", length = 20)
    private String accountCode;

    @Column(name = "account_name", length = 100)
    private String accountName;

    /** The bank statement this reconciliation rests on (nullable only for a phase-2 interim reconciliation). */
    @Column(name = "statement_id", columnDefinition = "UUID")
    private UUID statementId;

    @Column(name = "statement_start_date", nullable = false)
    private LocalDate statementStartDate;

    @Column(name = "statement_end_date", nullable = false)
    private LocalDate statementEndDate;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "statement_opening_balance", precision = 19, scale = 4)
    private BigDecimal statementOpeningBalance;

    @Column(name = "statement_closing_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal statementClosingBalance;

    @Column(name = "gl_ending_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal glEndingBalance;

    @Column(name = "difference", precision = 19, scale = 4, nullable = false)
    private BigDecimal difference = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReconciliationStatus status = ReconciliationStatus.IN_PROGRESS;

    @OneToMany(mappedBy = "reconciliation", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<BankReconciliationAdjustment> adjustments = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by", length = 50)
    private String finalizedBy;

    // ---- explicit equation (E3), opening terms and unexplained counts (§3.7; filled by S4) ----

    @Column(name = "gl_opening_balance", precision = 19, scale = 4)
    private BigDecimal glOpeningBalance;

    @Column(name = "approved_gl_ending_balance", precision = 19, scale = 4)
    private BigDecimal approvedGlEndingBalance;

    @Column(name = "sum_outstanding_ledger_items", precision = 19, scale = 4)
    private BigDecimal sumOutstandingLedgerItems;

    @Column(name = "sum_outstanding_bank_items", precision = 19, scale = 4)
    private BigDecimal sumOutstandingBankItems;

    @Column(name = "sum_late_adjustments", precision = 19, scale = 4)
    private BigDecimal sumLateAdjustments;

    @Column(name = "sum_opening_adjustments", precision = 19, scale = 4)
    private BigDecimal sumOpeningAdjustments;

    /** The account baseline that applies to this window (§3.1). */
    @Column(name = "baseline_date")
    private LocalDate baselineDate;

    @Column(name = "adjusted_bank_balance", precision = 19, scale = 4)
    private BigDecimal adjustedBankBalance;

    @Column(name = "adjusted_book_balance", precision = 19, scale = 4)
    private BigDecimal adjustedBookBalance;

    @Column(name = "sum_unexplained_bank", precision = 19, scale = 4)
    private BigDecimal sumUnexplainedBank;

    @Column(name = "count_unexplained_bank")
    private Integer countUnexplainedBank;

    @Column(name = "sum_unexplained_ledger", precision = 19, scale = 4)
    private BigDecimal sumUnexplainedLedger;

    @Column(name = "count_unexplained_ledger")
    private Integer countUnexplainedLedger;

    @Column(name = "opening_difference", precision = 19, scale = 4)
    private BigDecimal openingDifference;

    /** {@code YearMonth.from(statementEndDate)} for attribution and readiness; not a window constraint. */
    @Column(name = "accounting_period_code", length = 7)
    private String accountingPeriodCode;

    // ---- lifecycle actors (§3.7, §3.8; filled by S5) ----

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by", length = 50)
    private String submittedBy;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_reason", length = 1000)
    private String invalidationReason;

    @Column(name = "invalidated_by_journal_entry_id", columnDefinition = "UUID")
    private UUID invalidatedByJournalEntryId;

    @Column(name = "supersedes_reconciliation_id", columnDefinition = "UUID")
    private UUID supersedesReconciliationId;

    @Column(name = "superseded_by_reconciliation_id", columnDefinition = "UUID")
    private UUID supersededByReconciliationId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 1000)
    private String cancelReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** The caller's UUIDv7 {@code requestId} of the create command; a replay finds this row (§6.3; S4, #2303). */
    @Column(name = "request_id", columnDefinition = "UUID")
    private UUID requestId;

    /**
     * The SHA-256 of the command that created this row under {@code requestId}, when it has one (a supersede; S5,
     * #2304): a reused {@code requestId} with another payload is {@code IDEMPOTENCY_CONFLICT}, not a replay.
     */
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @PrePersist
    void onPrePersist() {
        if (createdBy == null) {
            createdBy = SecurityContextHelper.isAuthenticated()
                    ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                    : SYSTEM;
        }
        if (status == null) {
            status = ReconciliationStatus.IN_PROGRESS;
        }
    }

    public BankReconciliation(UUID reconciliationId) {
        this.reconciliationId = reconciliationId;
    }

    /** Add an adjustment and wire its parent back-reference. */
    public void addAdjustment(BankReconciliationAdjustment adjustment) {
        adjustment.setReconciliation(this);
        adjustments.add(adjustment);
    }

    @Transient
    public UUID getGlAccountId() {
        return glAccount != null ? glAccount.getGlAccountId() : null;
    }

    public void setGlAccountId(UUID glAccountId) {
        this.glAccount = glAccountId == null ? null : new GLAccount(glAccountId);
    }
}
