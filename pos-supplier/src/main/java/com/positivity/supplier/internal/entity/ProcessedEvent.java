package com.positivity.supplier.internal.entity;

import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Idempotency log for consumed events (ADR-0044 §4), keyed by the envelope's {@code eventId}.
 *
 * <p>At-least-once delivery means a consumer will see the same fact twice; this row is what makes
 * the second delivery a no-op. Unsupported event types are recorded too, so the owner's
 * reconciliation manifest counts every fact in a window rather than reporting the ones this module
 * chose to ignore as missing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@TenantGlobal(
        reason = "consumer idempotency ledger keyed by eventId, deduplicated across tenants; the tenant the fact"
                + " was applied under is carried as data for per-tenant reconciliation (db/tenancy-global-tables.txt)")
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    /** Producing domain, per the repo-wide convention (manifest scans key on it). */
    @Column(name = "owner", nullable = false, length = 64)
    private String owner;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * Tenant the fact was applied under (ADR-0062 §3), stamped from the tenant bound to the thread
     * when the row is persisted — the tenant the record interceptor read from the message header.
     * Reconciliation-manifest window scans filter on it so one tenant's manifest math never sees
     * another tenant's eventIds; a row recorded with no tenant bound belongs to no manifest. Carried
     * as data, never as a discriminator: the table is global and the eventId key deduplicates across
     * tenants.
     */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @PrePersist
    void stampTenantFromContext() {
        if (tenantId == null) {
            tenantId = TenantContext.current().orElse(null);
        }
    }
}
