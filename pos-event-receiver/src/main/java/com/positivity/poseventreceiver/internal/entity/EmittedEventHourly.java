package com.positivity.poseventreceiver.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection over the TimescaleDB continuous aggregate view
 * emitted_event_hourly, one row per (bucket, tenant, event type).
 *
 * <p>Per-tenant observability with global rollups (ADR-0062 plan WS6, decided 2026-09-10): the
 * aggregate is grouped by {@code tenant_id}, so a tenant's statistics are its own rows and the
 * global view is the sum across tenants. Like {@link EmittedEvent}, the view has no row-level
 * security (TimescaleDB excludes it from continuous aggregates); {@code tenant_id} is a data
 * column that every query in {@code EmittedEventHourlyRepository} names, except the one
 * cross-tenant rollup, which is reviewed and platform-only.
 */
@Entity
@TenantGlobal(
        reason = "continuous aggregate over emitted_event, which has no row-level security; grouped by tenant_id,"
                + " a data column every reader names, with the platform-only rollup summing across tenants")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "emitted_event_hourly")
@IdClass(EmittedEventHourlyId.class)
public class EmittedEventHourly {

    @Id
    @Column(name = "bucket")
    private Instant bucket;

    /** Producing tenant of the bucketed events (ADR-0062 plan WS6). */
    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "event_type")
    private String eventType;

    @Column(name = "event_count")
    private long eventCount;

    @Column(name = "avg_elapsed_ms")
    private BigDecimal avgElapsedMs;

    @Column(name = "p95_elapsed_ms")
    private double p95ElapsedMs;

    @Column(name = "p99_elapsed_ms")
    private double p99ElapsedMs;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: this is a read-only projection
     * over a TimescaleDB continuous aggregate, keyed by (bucket, tenantId, eventType), not a
     * UUID-keyed aggregate. Nothing is ever inserted through this entity, so there is no
     * identifier to generate.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
