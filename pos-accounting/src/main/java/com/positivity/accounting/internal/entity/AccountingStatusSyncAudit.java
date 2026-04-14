package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Append-only audit record for every accounting status synchronization event
 * processed
 * by {@link com.positivity.accounting.service.AccountingStatusSyncService}.
 *
 * <p>
 * Satisfies AC5 (CAP-251 Story #5): every sync event is persisted with audit
 * fields
 * including invoiceId, old/new status, eventId, syncedAt, sync source, and
 * latency.
 * </p>
 *
 * <p>
 * This entity is immutable — no update or delete operations are expected.
 * </p>
 */
@Entity
@Immutable
@Table(name = "accounting_status_sync_audit")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingStatusSyncAudit {

    @Id
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 50, updatable = false)
    private AccountingStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 50, nullable = false, updatable = false)
    private AccountingStatus newStatus;

    @Column(name = "event_id", length = 255, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "synced_at", nullable = false, updatable = false)
    private Instant syncedAt;

    @Column(name = "sync_source", length = 100, nullable = false, updatable = false)
    private String syncSource;

    @Column(name = "latency_ms", updatable = false)
    private Long latencyMs;
}
