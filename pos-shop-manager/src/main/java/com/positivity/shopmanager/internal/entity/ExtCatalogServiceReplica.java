package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
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
 * Read-only replica of a catalog service, fed by {@code catalog.events.v1}
 * ({@code catalog.service.updated}; CAP-329, ADR-0044 §6). pos-catalog owns the service and its
 * skill requirement; only {@link com.positivity.shopmanager.internal.service.CatalogEventsListener}
 * writes this table and its {@link ExtCatalogServiceSkillReplica} children.
 *
 * <p>{@link #requirementsConfiguredAt} null means the requirements were never configured — the
 * scheduler warns, never denies (D4); a timestamp with no children means unconstrained. Retired
 * services stay as {@code active=false} rows so a booking that names one can be told so.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_catalog_service")
public class ExtCatalogServiceReplica extends TenantScopedEntity {

    @Id
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "name")
    private String name;

    @Column(name = "operation_code", length = 64)
    private String operationCode;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "requirements_configured_at")
    private Instant requirementsConfiguredAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True once the owner has declared requirements — even an empty set. */
    public boolean isRequirementsConfigured() {
        return requirementsConfiguredAt != null;
    }

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
