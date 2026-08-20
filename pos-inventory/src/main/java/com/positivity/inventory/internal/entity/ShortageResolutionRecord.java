package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ShortageResolutionOption;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Idempotency + audit record for an executed shortage resolution (odoo-parity G2, issue #1049).
 *
 * <p>{@code POST /shortage/resolve} executes the chosen {@link ShortageResolutionOption} atomically
 * and persists ONE row keyed by the caller-supplied {@code idempotencyKey}. A replayed request with
 * the same key returns the stored result unchanged rather than executing the option a second time —
 * the unique index on {@code idempotency_key} is the cross-instance backstop, mirroring the G1
 * backorder duplicate-open guard.
 *
 * <p>{@code artifactType}/{@code artifactId} reference the artifact the resolution created (a
 * backorder, a substitute reservation, a transfer order, or a purchase suggestion); CANCEL_LINE
 * creates no artifact and leaves both null.
 *
 * <p>Timestamps follow ADR-0024 (Spring Data JPA auditing via {@link AuditingEntityListener} and an
 * injected {@code Clock}).
 */
@Entity
@Table(
        name = "shortage_resolution_record",
        uniqueConstraints =
                @UniqueConstraint(name = "ux_shortage_resolution_idempotency_key", columnNames = "idempotency_key"),
        indexes = {
            @Index(name = "idx_shortage_resolution_allocation", columnList = "allocation_id"),
            @Index(name = "idx_shortage_resolution_workorder_line", columnList = "workorder_line_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ShortageResolutionRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID resolutionId;

    /** Caller-supplied retry key; unique — the idempotency guard for the resolve POST. */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    /** Allocation the shortage was resolved for. */
    @Column(name = "allocation_id", nullable = false)
    private UUID allocationId;

    /** Workorder line whose demand was short; null when the caller did not supply one. */
    @Column(name = "workorder_line_id")
    private UUID workorderLineId;

    /** SKU / stock-item identifier that was short. */
    @Column(name = "sku", nullable = false)
    private String sku;

    /** Quantity that was short. */
    @Column(name = "short_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal shortQuantity;

    /** Site the demand was short at; null when the caller did not supply one. */
    @Column(name = "location_id")
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false, length = 30)
    private ShortageResolutionOption optionType;

    /** Resulting status, e.g. RESOLVED. */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    /** Kind of artifact created (BACKORDER, RESERVATION, TRANSFER_ORDER, PURCHASE_SUGGESTION, NONE). */
    @Column(name = "artifact_type", nullable = false, length = 40)
    private String artifactType;

    /** Identifier of the created artifact; null for CANCEL_LINE. */
    @Column(name = "artifact_id")
    private UUID artifactId;

    /** When the resolution executed. */
    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
