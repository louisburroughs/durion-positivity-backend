package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.enums.AuditAccessKind;
import com.positivity.supplier.internal.enums.AuditPayloadOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Record that someone read an exchange payload (ADR-0050 §7: "who read which exchange, when").
 *
 * <p>Separate from {@link ExchangeAuditEntity} because an access row has no attempt, outcome, vendor
 * or payload — see the V4 migration comment for why folding it in would have required loosening the
 * CHECK constraints that make the exchange table's guarantees real.
 *
 * <p>Only <strong>payload</strong> reads are recorded; see {@link AuditAccessKind} for what is
 * deliberately excluded and why.
 *
 * <p>No {@code @Version} and no updated-at: like an exchange row, this records something that
 * happened. Nothing updates it after insert, and unlike the exchange row nothing purges it either —
 * retention is permanent, because a read history that expired before the metadata it describes would
 * leave exchanges whose access trail had silently vanished.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_audit_access",
        indexes = {
            @Index(name = "idx_saccess_exchange", columnList = "exchange_audit_id"),
            @Index(name = "idx_saccess_actor_time", columnList = "accessed_by, accessed_at"),
            @Index(name = "idx_saccess_accessed_at", columnList = "accessed_at"),
            @Index(name = "idx_saccess_correlation", columnList = "correlation_id")
        })
public class AuditAccessEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "audit_access_id", updatable = false, nullable = false)
    private UUID auditAccessId;

    /** The exchange read. Plain UUID, no FK — the access record outlives what it references. */
    @Column(name = "exchange_audit_id", nullable = false, updatable = false)
    private UUID exchangeAuditId;

    /** Denormalised so the row is interpretable without joining a possibly-purged exchange row. */
    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, updatable = false, length = 64)
    private SupplierCapability capability;

    /** Subject, from the security context (ADR-0018). Never client-supplied. */
    @Column(name = "accessed_by", nullable = false, updatable = false, length = 255)
    private String accessedBy;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_kind", nullable = false, updatable = false, length = 32)
    private AuditAccessKind accessKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "payload_outcome", nullable = false, updatable = false, length = 32)
    private AuditPayloadOutcome payloadOutcome;

    /**
     * Correlation id of the request (or scheduled page) that caused this read, reused from the ambient
     * {@code SupplierCorrelationContext} scope that {@code SupplierCorrelationFilter} opens per request.
     * Nullable only because rows predating V6 genuinely captured none; the recorder always writes one.
     */
    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;
}
