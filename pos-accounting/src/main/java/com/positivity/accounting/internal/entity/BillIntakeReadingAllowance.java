package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Accounting's copy of one tenant's monthly document-reading allowance (CAP:550 S45, #2619; AW63, AW68), fed by
 * {@code tenant.reading-allowance.changed} on {@code tenant.events.v1} through {@code TenantEventsListener} and read
 * through {@code TenantReadingAllowanceReader}. One row per tenant, kept only once a fact has arrived;
 * {@link #aggregateVersion} lets an older fact be ignored.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bill_intake_reading_allowance")
public class BillIntakeReadingAllowance extends TenantScopedEntity implements Persistable<UUID> {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("the tenant's own id, the fact's payload.tenantId; this replica stores it verbatim")
    @Column(name = "reading_allowance_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID readingAllowanceId;

    @Column(name = "tier_code", nullable = false, length = 32)
    private String tierCode;

    /** The monthly allowance, in {@link #currency} (ADR-0067 PC-3). */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 code of {@link #amount}. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When the allowance applies from (the assignment or the tier change). */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public UUID getId() {
        return readingAllowanceId;
    }

    /** The key is assigned by the writer, so "new" is "never saved": {@code updatedAt} is stamped on the first save. */
    @Override
    public boolean isNew() {
        return updatedAt == null;
    }
}
