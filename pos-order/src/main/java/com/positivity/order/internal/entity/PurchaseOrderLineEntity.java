package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "purchase_order_line")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PurchaseOrderLineEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrderEntity purchaseOrder;

    @Column(nullable = false)
    private Integer lineNumber;

    private UUID skuId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantityDecimal;

    @Column(nullable = false)
    private Long unitCostMinor;

    @Column(nullable = false)
    private Long lineTotalMinor;

    private Long taxMinor;

    private String taxCodeId;

    private String glAccountId;

    /** Open quantity: derived (#2635) as {@code max(0, ordered − received − returned − cancelled)}, stored and published. */
    @Column(precision = 18, scale = 6)
    private BigDecimal openQuantityDecimal;

    /**
     * Base units received, net of receipt corrections and returns (CAP:550 #2635). {@code null} on a line written
     * before the figures existed and not yet touched: it then adopts {@code ordered − open} once (the V14 backfill
     * for stored rows); after that it is never null, so a figure that returns to zero is a zero, not a legacy row.
     */
    @Column(name = "received_quantity", precision = 18, scale = 6)
    private BigDecimal receivedQuantity;

    /** Minor units accrued for the received quantity (GRNI, 2100), net of corrections and returns. */
    @Builder.Default
    @Column(name = "received_accrued_minor", nullable = false)
    private Long receivedAccruedMinor = 0L;

    /** Base units returned to the vendor for credit (not reopened); a reopen for replacement takes them back. */
    @Builder.Default
    @Column(name = "returned_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    /** The returned quantity's value, minor units. */
    @Builder.Default
    @Column(name = "returned_value_minor", nullable = false)
    private Long returnedValueMinor = 0L;

    /** Base units short-closed: the vendor will not ship them (line cancel, close). */
    @Builder.Default
    @Column(name = "cancelled_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    /** The cancelled quantity's value, minor units. */
    @Builder.Default
    @Column(name = "cancelled_value_minor", nullable = false)
    private Long cancelledValueMinor = 0L;

    /** UoM the line was keyed in, when it differed from base (odoo-parity B2, #1034). */
    @Column(name = "document_uom", length = 32)
    private String documentUom;

    /** Quantity as keyed in {@code documentUom}; {@code quantityDecimal} holds the base quantity. */
    @Column(name = "document_quantity", precision = 18, scale = 6)
    private BigDecimal documentQuantity;

    /** Effective base-per-document-unit factor applied at conversion time (audit). */
    @Column(name = "conversion_factor", precision = 20, scale = 6)
    private BigDecimal conversionFactor;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
