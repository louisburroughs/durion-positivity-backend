package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only replica of one pos-inventory goods receipt (#2750, CAP:550 S19 follow-up; ADR-0044 R3). Written only from
 * the {@code goodsreceipt.recorded} fact on {@code inventory.events.v1}, by {@code GoodsReceiptReplica}; the
 * "deliveries not billed" work item reads it. The vendor and the purchase order's number are not copied: the read joins
 * {@link ExtOrderPurchaseOrder}.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_inventory_goods_receipt")
public class ExtInventoryGoodsReceipt extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("pos-inventory's receiptId, from the fact payload")
    @Column(name = "goods_receipt_id", nullable = false, columnDefinition = "UUID")
    private UUID goodsReceiptId;

    /** Null when the fact carries none. */
    @Column(name = "receipt_number", length = 255)
    private String receiptNumber;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    /** The latest fact's {@code totalAccruedAmountMinor}, in minor units of {@link #currencyCode}. */
    @Column(name = "accrued_amount_minor", nullable = false)
    private long accruedAmountMinor;

    /** The latest fact's currency; null when it states none (ADR-0067 PC-9), never defaulted. */
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    /** {@code occurredAt} of the first fact for the receipt; kept when a corrected fact re-emits it. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Set by auditing on every write. */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
