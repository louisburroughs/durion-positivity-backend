package com.positivity.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Base class of every entity whose table is tenant-scoped (ADR-0062 §2, §4).
 *
 * <p>Hibernate stamps {@code tenant_id} from the bound {@link TenantContext} on persist, appends
 * {@code tenant_id = ?} to every derived and JPQL query, and rejects an update whose row belongs to
 * another tenant. There is no setter: application code never chooses a tenant, and an ArchUnit rule
 * forbids assigning the field. Row-level security in Postgres remains the authoritative layer; this
 * is defense in depth and what gives H2 tests some tenancy behaviour.
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private @Nullable UUID tenantId;

    /** The owning tenant, set by Hibernate on persist; {@code null} only before the first flush. */
    public @Nullable UUID getTenantId() {
        return tenantId;
    }
}
