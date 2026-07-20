package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.ReconciliationStatus;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
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
 * Manual CSV bank reconciliation header (Story F2, issue #965, decisions D-5/D-6).
 *
 * <p>One row per statement import for one reconcilable GL cash account. Single
 * currency per reconciliation (hard invariant; {@link #currency} governs). The
 * reconciliation is created {@link ReconciliationStatus#IN_PROGRESS} with its
 * imported statement lines; {@link #glEndingBalance} is snapshotted at import from
 * posted journal-entry lines on the account as-of {@link #statementDate}. Finalizing
 * requires the balance gate to net to zero (difference within ±0.01) and flips the
 * status to {@link ReconciliationStatus#FINALIZED}.
 *
 * <p>Replaces the dead baseline {@code reconciliation} entity, which modelled its
 * children as untyped jsonb; here lines and adjustments are proper relational
 * children ({@code @OneToMany}, FK per ADR-0013 UUIDv7 ids).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"glAccount", "statementLines", "adjustments"})
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bank_reconciliation",
        indexes = {
            @Index(name = "idx_bank_reconciliation_account", columnList = "gl_account_id"),
            @Index(name = "idx_bank_reconciliation_status", columnList = "status")
        })
public class BankReconciliation {

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

    @Column(name = "period_start_date", nullable = false)
    private LocalDate periodStartDate;

    @Column(name = "period_end_date", nullable = false)
    private LocalDate periodEndDate;

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "statement_ending_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal statementEndingBalance;

    @Column(name = "gl_ending_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal glEndingBalance;

    @Column(name = "difference", precision = 19, scale = 4, nullable = false)
    private BigDecimal difference = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReconciliationStatus status = ReconciliationStatus.IN_PROGRESS;

    @OneToMany(mappedBy = "reconciliation", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<BankReconciliationLine> statementLines = new ArrayList<>();

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

    /** Add a statement line and wire its parent back-reference. */
    public void addStatementLine(BankReconciliationLine line) {
        line.setReconciliation(this);
        statementLines.add(line);
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
