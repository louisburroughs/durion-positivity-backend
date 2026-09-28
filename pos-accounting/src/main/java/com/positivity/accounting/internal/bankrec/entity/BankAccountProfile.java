package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The thin bank-account profile of a reconcilable GL cash account (SPEC-manual-bank-reconciliation
 * §3.1, §6.4, D21; story S1, #2300), 1:1 with the GL account. No account or routing number is
 * stored. The intake creates and maintains it from story S2 on; nothing edits
 * {@link #reconciliationBaselineDate} directly.
 *
 * <p>{@link #currency} is required and carries no default (ADR-0067 R-2, R-4); in phase 1 the only
 * accepted value is the ledger currency (D18), which story S2 enforces.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_account_profile")
public class BankAccountProfile extends TenantScopedEntity {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("the reconciled GL account's own UUIDv7; the profile is 1:1 with that account (D21)")
    @Column(name = "gl_account_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID glAccountId;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    /** Last digits of the bank account number, for display only. */
    @Column(name = "account_mask", length = 8)
    private String accountMask;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** The column mapping the next file import on this account defaults to (§3.3). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_column_mapping")
    private Map<String, Object> defaultColumnMapping;

    @Column(name = "statement_cycle_hint", length = 32)
    private String statementCycleHint;

    /** The first day from which the account's items must be explained (§3.1); maintained by the intake. */
    @Column(name = "reconciliation_baseline_date")
    private LocalDate reconciliationBaselineDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BankAccountProfile(UUID glAccountId) {
        this.glAccountId = glAccountId;
    }

    @PrePersist
    void onPrePersist() {
        if (createdBy == null) {
            createdBy = SecurityContextHelper.isAuthenticated()
                    ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                    : SYSTEM;
        }
    }
}
