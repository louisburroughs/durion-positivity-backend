package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.TaxLiabilitySnapshotStatus;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Frozen Sales-Tax Liability snapshot for a CLOSED accounting period (issue #998, Phase-2 scope
 * item 2 — period-close alignment + auditable freeze).
 *
 * <p>Captures the T8 report (issue #966) exactly as generated over the period's date range at
 * freeze time, plus a canonical SHA-256 {@link #contentHash} so the frozen figures can later be
 * re-derived from the live replicas and compared. <strong>Content columns are immutable after
 * insert</strong>; the only permitted change is the {@code ACTIVE -> SUPERSEDED} status transition
 * (with {@code supersededAt}/{@code supersededBy}) when a reopened-and-re-closed period is frozen
 * again. Provider-neutral by design — no filing-provider identifiers (see the #998 decision
 * record).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "rows")
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "tax_liability_snapshot",
        indexes = {@Index(name = "idx_tax_liability_snapshot_period_code", columnList = "period_code")})
public class TaxLiabilitySnapshot {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "snapshot_id", nullable = false, columnDefinition = "UUID")
    private UUID snapshotId;

    @Column(name = "period_id", nullable = false, columnDefinition = "UUID")
    private UUID periodId;

    /** Period code in {@code YYYY-MM} format, denormalized from {@link AccountingPeriod}. */
    @Column(name = "period_code", length = 7, nullable = false)
    private String periodCode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private TaxLiabilitySnapshotStatus status = TaxLiabilitySnapshotStatus.ACTIVE;

    /** The period's {@code closedAt} at freeze time — ties the snapshot to a specific close. */
    @Column(name = "period_closed_at", nullable = false)
    private Instant periodClosedAt;

    /** SHA-256 (lowercase hex) over the canonical report serialization; see TaxLiabilityCanonicalizer. */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "total_taxable_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalTaxableBase;

    @Column(name = "total_exempt_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalExemptBase;

    @Column(name = "total_tax_collected_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalTaxCollectedGross;

    @Column(name = "total_credits_netted", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCreditsNetted;

    @Column(name = "total_net_tax", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalNetTax;

    @Column(name = "tax_payable_account_code", length = 20, nullable = false)
    private String taxPayableAccountCode;

    @Column(name = "gl_net_activity", nullable = false, precision = 19, scale = 4)
    private BigDecimal glNetActivity;

    @Column(name = "gl_drift", nullable = false, precision = 19, scale = 4)
    private BigDecimal glDrift;

    @Column(name = "reconciled", nullable = false)
    private boolean reconciled;

    /** The ACTIVE snapshot this one replaced, when frozen with {@code supersede=true}; else null. */
    @Column(name = "supersedes_snapshot_id", columnDefinition = "UUID")
    private UUID supersedesSnapshotId;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "superseded_by", length = 50)
    private String supersededBy;

    @CreatedDate
    @Column(name = "frozen_at", nullable = false, updatable = false)
    private Instant frozenAt;

    @Column(name = "frozen_by", length = 50, nullable = false, updatable = false)
    private String frozenBy;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rowOrder ASC")
    private List<TaxLiabilitySnapshotRow> rows = new ArrayList<>();

    public void addRow(TaxLiabilitySnapshotRow row) {
        row.setSnapshot(this);
        rows.add(row);
    }

    @PrePersist
    public void onPrePersist() {
        this.frozenBy = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
