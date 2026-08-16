package com.positivity.order.internal.entity;

import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.shared.id.UUIDv7Id;
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
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PurchaseOrderEntity {

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

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PurchaseOrderLineEntity> lines = new ArrayList<>();
}
