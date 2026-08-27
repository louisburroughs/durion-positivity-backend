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
 * {@code workorder.events.v1} fact for the same {@link #workorderId} (a) post-dates {@link
 * #requestedAt} and (b) carries a resulting invoice id other than {@link #priorInvoiceId} — see
 * that listener's class javadoc for why both conditions are required (#1537 F4). A row that never
 * resolves is reaped to {@link #STATUS_FAILED} after a TTL.
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

    /**
     * Terminal non-success state (#1537 F4): {@code KafkaCommandListener} in pos-workorder
     * swallows business failures (workorder missing / not eligible), so a genuinely failed
     * regeneration emits no fact at all and would otherwise leave the row {@code PENDING}
     * forever. Reaped from {@code PENDING} once {@link #requestedAt} exceeds the configured TTL
     * (see {@code WorkorderEventsListener#reapExpiredRequests}).
     */
    public static final String STATUS_FAILED = "FAILED";

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

    /**
     * The invoiceId already linked to {@link #workorderId} at the moment this request was
     * published, if any (#1537 F4). {@code WorkorderEventsListener} refuses to resolve this row
     * from a fact carrying this same id — such a fact only echoes the pre-existing invoice
     * (e.g. an unrelated workorder edit), not evidence that regeneration produced anything.
     */
    @Column(name = "prior_invoice_id", columnDefinition = "UUID", updatable = false)
    private UUID priorInvoiceId;

    @Column(name = "requested_by", length = 255, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
