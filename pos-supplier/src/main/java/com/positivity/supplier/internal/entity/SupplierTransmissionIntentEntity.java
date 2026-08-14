package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One transmission intent: the idempotency identity of sending a purchase order to a vendor
 * (ADR-0052 §1), and the outbox row that dispatch drains.
 *
 * <h2>Why the intent row <em>is</em> the outbox row</h2>
 *
 * ADR-0052 §2 requires the intent and its outbox row to be written in one transaction. They are
 * written as one row, which satisfies that by construction rather than by care: there is no
 * window in which an intent exists without work queued, or work queued for an intent that was
 * rolled back. Dispatch drains {@link TransmissionAttemptState#PENDING} rows in id order, and
 * UUIDv7 ids are time-ordered, so a vendor sees a customer's orders in the order they were
 * placed.
 *
 * <h2>{@code activeIntentKey} is the duplicate-order guard</h2>
 *
 * ADR-0052 §1 requires at most one <em>active</em> intent per
 * {@code (vendorProfileId, intentType, purchaseOrderId, revision)}. That is enforced by a plain
 * unique index over this one derived column rather than a partial index over four, because a
 * partial unique index is not portable to the H2 the dev profile runs on — and a constraint that
 * only exists in production is not a constraint, it is a hope.
 *
 * <p>The column holds the tuple while the intent has a claim on it and is cleared to null when
 * the intent ends in a way that makes re-ordering legitimate ({@code REJECTED}, {@code FAILED},
 * {@code CANCELLED}); nulls do not collide in a unique index on either database.
 * <strong>{@code CONFIRMED} keeps its key forever</strong> — a vendor that accepted an order
 * holds that tuple for good, so a re-issued command for it is recognised as the repeat it is
 * instead of becoming a second truckload.
 *
 * <h2>What is not stored here</h2>
 *
 * No vendor payloads. The raw request and response documents live in the exchange audit, where
 * ADR-0050 §7 governs their encryption, redaction, retention and access. A second copy alongside
 * the ledger would be a second copy under none of those rules. What is kept instead is
 * {@code lastStatusFingerprint} — a hash — which is enough to tell whether a vendor's answer
 * changed without holding the answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_transmission_intent",
        indexes = {
            @Index(name = "idx_stintent_purchase_order", columnList = "purchase_order_id"),
            @Index(name = "idx_stintent_state", columnList = "attempt_state, transmission_intent_id"),
            @Index(name = "idx_stintent_polling", columnList = "status_polling_active, vendor_profile_id")
        })
public class SupplierTransmissionIntentEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "transmission_intent_id", updatable = false, nullable = false)
    private UUID transmissionIntentId;

    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /** Profile alias at mint time. Descriptive only — never a join key (ADR-0050 §1). */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;

    /** The ordering domain's human-readable order number; descriptive only. */
    @Column(name = "purchase_order_number", length = 64)
    private String purchaseOrderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent_type", nullable = false, updatable = false, length = 32)
    private SupplierPurchaseOrder.IntentType intentType;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    /**
     * The wire {@code DocumentID}/{@code CustomerReference}, derived deterministically from
     * {@link #transmissionIntentId} and immutable thereafter (ADR-0052 §1). Every retry of this
     * intent presents this same value; a new intent always gets a different one.
     */
    @Column(name = "document_id", nullable = false, updatable = false, length = 35)
    private String documentId;

    /** See the class note: the portable form of "at most one active intent per tuple". */
    @Column(name = "active_intent_key", length = 255)
    private String activeIntentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_state", nullable = false, length = 32)
    private TransmissionAttemptState attemptState;

    /** Receiving location the order is consigned to; null for a profile with no consignee. */
    @Column(name = "delivery_location_id")
    private UUID deliveryLocationId;

    /** Buyer account the order was placed under, snapshotted at mint time. */
    @Column(name = "billing_account_number", length = 64)
    private String billingAccountNumber;

    /** Norm version the dispatch used, for audit when a binding is later retargeted. */
    @Column(name = "protocol_version", length = 64)
    private String protocolVersion;

    /** The vendor's own order reference. An attribute, never a key (ADR-0013/0027). */
    @Column(name = "supplier_order_number", length = 64)
    private String supplierOrderNumber;

    @Column(name = "vendor_error_code", length = 16)
    private String vendorErrorCode;

    @Column(name = "vendor_reason", length = 2000)
    private String vendorReason;

    @Column(name = "dispatch_attempts", nullable = false)
    @Builder.Default
    private int dispatchAttempts = 0;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "result_at")
    private Instant resultAt;

    /**
     * Hash of the last order-status answer, used to publish
     * {@code supplier.orderstatus.changed} on transitions only.
     *
     * <p>A hash rather than the answer: polling a busy vendor every few minutes would otherwise
     * accumulate a commercial document per poll per order, outside the audit's retention and
     * encryption rules, to answer a question a 64-character string answers.
     */
    @Column(name = "last_status_fingerprint", length = 64)
    private String lastStatusFingerprint;

    /**
     * When the vendor's answer last <em>changed</em> — not when it was last asked for. Staleness
     * is measured against this, so a vendor answering identically every hour does not keep an
     * abandoned order alive forever.
     */
    @Column(name = "last_status_at")
    private Instant lastStatusAt;

    /** When the vendor was last asked. Drives poll ordering, and nothing else. */
    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_status_state", length = 32)
    private String lastStatusState;

    /**
     * Monotonic sequence stamped on every event published about this intent, so a consumer can
     * order and de-duplicate them without trusting broker delivery order.
     */
    @Column(name = "event_sequence", nullable = false)
    @Builder.Default
    private long eventSequence = 0L;

    /** Latest delivery date any line is scheduled for; bounds how long polling stays useful. */
    @Column(name = "latest_scheduled_delivery_date")
    private LocalDate latestScheduledDeliveryDate;

    /**
     * Whether the status poller still asks about this order. Polling continues well past
     * {@code CONFIRMED} — confirmation is where the delivery story starts, not where it ends —
     * and stops only when {@code OrderStatusCompletion} says there is nothing left to learn.
     */
    @Column(name = "status_polling_active", nullable = false)
    @Builder.Default
    private boolean statusPollingActive = false;

    @Column(name = "polling_stopped_reason", length = 64)
    private String pollingStoppedReason;

    /** Which ADR-0052 §4 action an operator took, when one was taken. */
    @Column(name = "resolution_action", length = 32)
    private String resolutionAction;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** The operator's free-text evidence for a manual resolution (ADR-0052 §4). */
    @Column(name = "resolution_evidence", length = 2000)
    private String resolutionEvidence;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The active-intent key for a tuple.
     *
     * @return the derived key an active intent claims
     */
    public static String activeKeyFor(
            UUID vendorProfileId, SupplierPurchaseOrder.IntentType intentType, UUID purchaseOrderId, int revision) {
        return vendorProfileId + "|" + intentType + "|" + purchaseOrderId + "|" + revision;
    }
}
