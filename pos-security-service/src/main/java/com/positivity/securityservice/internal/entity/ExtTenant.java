package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only replica of the public tenant projection fed by {@code tenant.events.v1} (ADR-0062 §7,
 * plan WS2b). pos-tenant owns the registry; nothing in this module may write the table except the
 * event consumer. Global on purpose: login resolves a slug here before any tenant is bound.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@TenantGlobal(reason = "public tenant projection consulted before a tenant is bound (login slug resolution)")
@Table(name = "ext_tenant")
public class ExtTenant {

    @Id
    @AssignedIdentifier("the tenant's own id, minted by pos-tenant and carried on tenant.events.v1; a replica of it")
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 63)
    private String slug;

    @Column(name = "display_name", length = 200)
    private String displayName;

    /** Raw lifecycle status: {@code PENDING}, {@code ACTIVE}, {@code SUSPENDED} or {@code DECOMMISSIONED}. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by pos-tenant; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
