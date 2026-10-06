package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
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
 * What the accounting template applier last did for a tenant (#2526): one row per tenant.
 *
 * <p>The applier locks this row for the length of a run, so the {@code tenant.created} listener,
 * the startup sweep and a second instance never apply to one tenant at once. A row with no
 * fingerprint was created to be locked and nothing has been applied yet.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "accounting_template_state",
        uniqueConstraints = {@UniqueConstraint(name = "uq_accounting_template_state_tenant", columnNames = "tenant_id")
        })
public class AccountingTemplateState extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "state_id", nullable = false, columnDefinition = "UUID")
    private UUID stateId;

    /** SHA-256 of the template last applied in full; {@code null} until the first apply. */
    @Column(name = "template_fingerprint", length = 64)
    private String templateFingerprint;

    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "adopted_count", nullable = false)
    private int adoptedCount;

    @Column(name = "refreshed_count", nullable = false)
    private int refreshedCount;

    @Column(name = "conflict_count", nullable = false)
    private int conflictCount;

    @Column(name = "withheld_count", nullable = false)
    private int withheldCount;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}
