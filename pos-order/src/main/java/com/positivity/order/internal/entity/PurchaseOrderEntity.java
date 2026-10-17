package com.positivity.order.internal.entity;

import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The commercial purchase order: what was ordered, from which vendor, at what price, on whose
 * approval (CAP-320, ADR-0049 §2).
 *
 * <h2>What this owns and what it does not</h2>
 *
 * pos-order is the only module that accepts a change to a purchase order. It does not own what
 * arrived: goods receipt is pos-inventory's, and {@code openQuantityDecimal} on the lines moves
 * here only in response to a receipt fact that module publishes. Two writable purchase orders is
 * the failure this split exists to prevent, and the second one always finds a caller.
 *
 * <p>Everything outside this module reads the order through the published
 * {@code purchaseorder.updated} fact and its own replica, never this table.
 */
@Entity
@Table(name = "purchase_order")
// Only changed columns are written (#2681): with every writer holding the row lock this is belt and braces, but it
// keeps a status-only write from ever carrying a stale header back to the row.
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PurchaseOrderEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID purchaseOrderId;

    @Column(nullable = false)
    private UUID vendorId;

    @Column(nullable = false, unique = true)
    private String poNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Integer versionNumber = 1;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Long subtotalMinor;

    @Column(nullable = false)
    private Long taxMinor;

    @Column(nullable = false)
    private Long grandTotalMinor;

    /**
     * Value of the goods still outstanding, ex-tax (#2633): the subtotal at create and revise, less each
     * goods receipt's accrual, floored at zero. {@code taxMinor} and {@code grandTotalMinor} describe the
     * document; this is what is still open.
     */
    private Long openBalanceMinor;


    private UUID shipToLocationId;

    private String paymentTermsId;

    private LocalDate poDate;

    private LocalDate expectedDeliveryDate;

    private String requestedBy;

    private String comment;

    private String approverId;

    private Instant approvalTimestamp;

    private String approvalNotes;

    @CreatedBy
    @Column(nullable = false, updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    // ── Transmission to the vendor (CAP-320 #1330) ───────────────────────────

    /**
     * The purchasing channel the order is sent through: pos-supplier's designated transmission profile alias for the
     * vendor, stamped from pos-order's vendor copy ({@code ext_supplier_vendor.purchase_order_supplier_ref}) at
     * create, vendor-changing revise, approve and, until the first send, transmit (#2713, Order ruling on #2682).
     * Never a buyer's field: which channel reaches a vendor is integration configuration.
     *
     * <p>Since S24 {@code vendorId} is the pos-supplier vendor id (a requested order may still carry a feed id until
     * its buyer revises it), but the alias is still what pos-supplier resolves a command by, and the copy is the
     * only place pos-order may read it (ADR-0044 R1). After the first send the stamp is frozen, so a revision
     * reaches the channel the original did. An order without one answers 422 {@code SUPPLIER_REF_MISSING}.
     */
    @Column(name = "supplier_ref", length = 100)
    private String supplierRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "transmission_state", nullable = false, length = 32)
    @Builder.Default
    private TransmissionState transmissionState = TransmissionState.NOT_TRANSMITTED;

    @Column(name = "transmission_intent_id")
    private UUID transmissionIntentId;

    @Column(name = "vendor_document_id")
    private String vendorDocumentId;

    @Column(name = "supplier_order_number")
    private String supplierOrderNumber;

    @Column(name = "transmission_rejection_reason", length = 64)
    private String transmissionRejectionReason;

    @Column(name = "transmission_vendor_reason", length = 1024)
    private String transmissionVendorReason;

    @Column(name = "transmission_requested_at")
    private Instant transmissionRequestedAt;

    /**
     * The vendor's clock as last reported. A result older than this is ignored, which is what makes
     * an out-of-order arrival harmless rather than a regression to a state already left behind.
     */
    @Column(name = "transmission_observed_at")
    private Instant transmissionObservedAt;

    /**
     * How many times this order has been transmitted. Distinguishes a first send from a re-order,
     * which decides whether the next request is {@code INITIAL} or {@code REPLACEMENT} — nothing in
     * the payload lets pos-supplier tell them apart, and getting it wrong is a second lorry.
     */
    @Column(name = "transmission_count", nullable = false)
    @Builder.Default
    private Integer transmissionCount = 0;

    /** The order's version as last transmitted; a later one makes the next request a REVISION. */
    @Column(name = "transmitted_version_number")
    private Integer transmittedVersionNumber;

    /**
     * {@link #transmittedVersionNumber} as it stood before the in-flight request overwrote it, so a
     * request pos-supplier refuses to dispatch (#2492) can be undone without changing what the next
     * send is classified as.
     */
    @Column(name = "prior_transmitted_version_number")
    private Integer priorTransmittedVersionNumber;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PurchaseOrderLineEntity> lines = new ArrayList<>();
}
