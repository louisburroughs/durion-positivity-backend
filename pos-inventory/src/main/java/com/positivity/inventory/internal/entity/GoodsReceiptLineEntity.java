package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
@Table(name = "goods_receipt_line")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class GoodsReceiptLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID receiptLineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private GoodsReceiptEntity goodsReceipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "po_line_id")
    private PurchaseOrderLineEntity poLine;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantityReceived;

    @Column(nullable = false)
    private Long unitCostMinor;

    private Long lineAccruedAmountMinor;

    private String lotNumber;

    /** UoM the line was keyed in, when it differed from base (odoo-parity B2, #1034). */
    @Column(name = "document_uom", length = 32)
    private String documentUom;

    /** Quantity as keyed in {@code documentUom}; {@code quantityReceived} holds the base quantity. */
    @Column(name = "document_quantity", precision = 18, scale = 6)
    private BigDecimal documentQuantity;

    /** Effective base-per-document-unit factor applied at conversion time (audit). */
    @Column(name = "conversion_factor", precision = 20, scale = 6)
    private BigDecimal conversionFactor;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
