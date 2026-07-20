package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.BankAdjustmentType;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
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
public class BankReconciliationAdjustment {

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
