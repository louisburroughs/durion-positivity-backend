package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
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
 * Accounting Period - monthly posting period (AD-012).
 *
 * Two-state lifecycle per decision D-7: OPEN -> CLOSED, reopenable with
 * mandatory justification. Periods are auto-provisioned OPEN on first posting
 * into a nonexistent period; a missing row therefore means OPEN.
 *
 * The {@code reopened*} columns record the most recent reopen only.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B1</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "accounting_period",
        uniqueConstraints = {@UniqueConstraint(name = "uq_accounting_period_code", columnNames = "period_code")},
        indexes = {@Index(name = "idx_accounting_period_status", columnList = "status")})
public class AccountingPeriod {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "period_id", nullable = false, columnDefinition = "UUID")
    private UUID periodId;

    /**
     * Period code in {@code YYYY-MM} format (e.g. "2026-07"). Unique.
     */
    @Column(name = "period_code", length = 7, nullable = false)
    private String periodCode;

    /**
     * First day of the month.
     */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /**
     * Last day of the month (inclusive).
     */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private AccountingPeriodStatus status = AccountingPeriodStatus.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopened_by", length = 50)
    private String reopenedBy;

    @Column(name = "reopen_justification", length = 1000)
    private String reopenJustification;

    // Audit fields
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;

    @PrePersist
    public void onPrePersist() {
        String currentUser = resolveActor();
        this.createdBy = currentUser;
        this.modifiedBy = currentUser;
    }

    @PreUpdate
    protected void onUpdate() {
        this.modifiedBy = resolveActor();
    }

    private static String resolveActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
