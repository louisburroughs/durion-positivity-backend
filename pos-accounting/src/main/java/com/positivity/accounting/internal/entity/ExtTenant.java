package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Accounting's copy of one tenant's locale (CAP:550 S44, #2618; ADR-0030 §2), fed by {@code tenant.events.v1} through
 * {@code TenantEventsListener} and read through {@code TenantLocaleResolver}. One row per tenant, kept only once a
 * fact has carried a locale; {@link #aggregateVersion} lets an older fact be ignored.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_tenant")
public class ExtTenant extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("the tenant's own id, the tenant fact's payload.tenantId; this replica stores it verbatim")
    @Column(name = "ext_tenant_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID extTenantId;

    /** BCP 47 tag as pos-tenant recorded it, e.g. {@code fr-CA}. */
    @Column(name = "locale", length = 35)
    private String locale;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
