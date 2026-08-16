package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing the vendor said about a purchase order (CAP-320 #1330).
 *
 * <h2>A timeline, not a current value</h2>
 *
 * "Confirmed, then despatched, then delivery slipped a week" is what a buyer chasing an order
 * actually needs, and each step carries dates the previous one did not. Collapsing that to the
 * latest status would answer "where is it?" and lose "what happened to it?", which is the question
 * asked when an order goes wrong.
 *
 * <p>Rows are appended in the order the events were <em>heard</em> and read in the order the vendor
 * <em>observed</em> them, so a status that arrives late still sits in the right place on the
 * timeline rather than at the end of it.
 */
@Entity
@Table(name = "purchase_order_transmission_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderTransmissionEvent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "transmission_intent_id")
    private UUID transmissionIntentId;

    /**
     * {@code CONFIRMED}, {@code REJECTED}, {@code STATUS_CHANGED} or {@code REVIEW_REQUIRED}.
     *
     * <p>The last is not a vendor answer at all — it records that the transmission stopped and
     * needs a person — but it belongs on the same timeline, because "nothing happened next, and
     * here is why" is part of the story of the order.
     */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** The vendor-reported status on a status change; null on a confirmation or rejection. */
    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "vendor_document_id")
    private String vendorDocumentId;

    @Column(name = "supplier_order_number")
    private String supplierOrderNumber;

    @Column(name = "vendor_reason", length = 1024)
    private String vendorReason;

    @Column(name = "despatch_date")
    private LocalDate despatchDate;

    @Column(name = "estimated_delivery_date")
    private LocalDate estimatedDeliveryDate;

    /** The vendor's clock: when it says this happened. Orders the timeline. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Ours: when we heard it. Kept apart from {@code observedAt} so a late arrival is visible. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
