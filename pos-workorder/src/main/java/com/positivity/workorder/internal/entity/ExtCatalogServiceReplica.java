package com.positivity.workorder.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event-fed replica of the catalog service master's operation taxonomy (#1569 Phase 1,
 * ADR-0058 §5), from {@code catalog.service.updated} schema v2.
 *
 * <p>Carries the vehicle-agnostic {@code defaultLaborHours} ONLY — the degraded/offline prefill
 * when the labor-time resolution edge cannot answer, never the vehicle-correct number. The
 * {@code operationCode} is what the overlap-aware estimated-hours summation uses to recognise a
 * line named in another line's included operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_catalog_service")
public class ExtCatalogServiceReplica {

    @Id
    @Column(name = "service_id", columnDefinition = "UUID")
    private UUID serviceId;

    @Column(name = "name")
    private String name;

    @Column(name = "operation_code")
    private String operationCode;

    @Column(name = "operation_category")
    private String operationCategory;

    @Column(name = "default_labor_hours", precision = 5, scale = 1)
    private BigDecimal defaultLaborHours;

    /** False on the delete tombstone: kept, never resolved. */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** Envelope aggregateVersion — the monotonic stale-event guard. */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the module's ArchUnit UUIDv7 rule, matching the other
     * {@code ext_*} replicas: the key IS a UUIDv7 — pos-catalog's service id — and minting a
     * local one would orphan the replica from its owner.
     */
    @jakarta.persistence.Transient
    public Class<?> uuidv7Dependency() {
        return com.positivity.shared.id.UUIDv7Id.class;
    }
}
