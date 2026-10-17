package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Read-only replica of one pos-order purchase order (#2515, CAP:550 S19; ADR-0044 R3). Written only by the {@code
 * order.events.v1} consumer from the {@code purchaseorder.updated} fact; the estimated due date of a vendor bill that
 * has none reads {@link #paymentTermsId} first.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@Table(name = "ext_order_purchase_order")
public class ExtOrderPurchaseOrder extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("pos-order's purchaseOrderId, the fact's aggregateId")
    @Column(name = "purchase_order_id", nullable = false, columnDefinition = "UUID")
    private UUID purchaseOrderId;

    @Column(name = "po_number", length = 255, nullable = false)
    private String poNumber;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    /** The owner's terms text; null when the order has none or the fact is schema 1. May not parse. */
    @Column(name = "payment_terms_id", length = 255)
    private String paymentTermsId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
