package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A reconciliation adjustment (Story F2, issue #965, decision D-6). Recording an
 * adjustment posts a real balanced journal entry (Dr/Cr the reconciled cash account
 * against the type's mapped counter account, through the accounting-period gate) and
 * stores that {@link #journalEntryId} here. Append-only within a reconciliation.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"reconciliation"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bank_reconciliation_adjustment",
        indexes = {@Index(name = "idx_bank_reconciliation_adjustment_recon", columnList = "reconciliation_id")})
public class BankReconciliationAdjustment extends TenantScopedEntity {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "adjustment_id", nullable = false, columnDefinition = "UUID")
    private UUID adjustmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_id", nullable = false)
    private BankReconciliation reconciliation;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", length = 32, nullable = false)
    private BankAdjustmentType adjustmentType;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "journal_entry_id", columnDefinition = "UUID", nullable = false)
    private UUID journalEntryId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    // ---- story S1 (#2300) columns, all nullable; their rules land with story S4 (SPEC §3.5, §6.4) ----

    /** Caller-generated UUIDv7 for idempotent creation (§6.3). */
    @Column(name = "request_id", columnDefinition = "UUID")
    private UUID requestId;

    /** The date the adjustment journal entry was actually posted at (D7). */
    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(name = "posted_period_code", length = 7)
    private String postedPeriodCode;

    /** The bank transaction this adjustment explains. */
    @Column(name = "bank_transaction_id", columnDefinition = "UUID")
    private UUID bankTransactionId;

    @Column(name = "override_justification", length = 1000)
    private String overrideJustification;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private AdjustmentStatus status = AdjustmentStatus.POSTED;

    @Column(name = "reversal_journal_entry_id", columnDefinition = "UUID")
    private UUID reversalJournalEntryId;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;

    @Column(name = "reversal_reason", length = 1000)
    private String reversalReason;

    /** The counter bank account of a {@code TRANSFER} (D9; the type itself ships with S4). */
    @Column(name = "counter_gl_account_id", columnDefinition = "UUID")
    private UUID counterGlAccountId;

    @Column(name = "justification", length = 1000)
    private String justification;

    /** The accepted match whose residual this {@code OTHER} adjustment settles (§4.6). */
    @Column(name = "settles_match_id", columnDefinition = "UUID")
    private UUID settlesMatchId;

    /** The acknowledged statement whose opening gap this {@code OTHER} adjustment bridges (§4.2). */
    @Column(name = "bridges_statement_id", columnDefinition = "UUID")
    private UUID bridgesStatementId;

    @PrePersist
    void onPrePersist() {
        if (createdBy == null) {
            createdBy = SecurityContextHelper.isAuthenticated()
                    ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                    : SYSTEM;
        }
    }

    @Transient
    public UUID getReconciliationId() {
        return reconciliation != null ? reconciliation.getReconciliationId() : null;
    }
}
