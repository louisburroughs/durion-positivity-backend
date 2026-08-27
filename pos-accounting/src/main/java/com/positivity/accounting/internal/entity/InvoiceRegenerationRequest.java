package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracks one async invoice-regeneration command published to {@code workorder.commands.v1}
 * (issue #1537, D1), so the regeneration endpoint can report a genuine terminal state instead
 * of returning PENDING forever, and a repeat call carrying an idempotency key that already
 * completed can short-circuit without re-publishing.
 *
 * <p>Created {@link #STATUS_PENDING} by {@code InvoiceRegenerationServiceImpl} when the command
 * is published; resolved to {@link #STATUS_COMPLETED} by {@code WorkorderEventsListener} once a
 * {@code workorder.events.v1} fact for the same {@link #workorderId} carries the resulting
 * invoice id.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "invoice_regeneration_request")
public class InvoiceRegenerationRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workorder_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID workorderId;

    @Column(name = "command_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID commandId;

    @Column(name = "idempotency_key", length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "result_invoice_id", columnDefinition = "UUID")
    private UUID resultInvoiceId;

    @Column(name = "requested_by", length = 255, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
