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
@Table(name = "asn_line")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AsnLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID asnLineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id", nullable = false)
    private AdvanceShippingNoticeEntity asn;

    /**
     * The purchase order this belongs to, by id (CAP-320 #1334).
     *
     * <p>A plain identifier rather than a JPA association: the order lives in pos-order now, so
     * there is nothing here to join to. What the order says is read from the
     * {@code ext_purchase_order} projection, which is the cross-domain read path (ADR-0044 R3).
     */
    @Column(name = "po_id", nullable = false)
    private UUID purchaseOrderId;

    /**
     * The purchase-order line this belongs to, by id (CAP-320 #1334). A plain identifier for the
     * same reason as {@code poId}: the line lives in pos-order.
     */
    @Column(name = "po_line_id")
    private UUID poLineId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantityShipped;

    @Column(precision = 18, scale = 6)
    private BigDecimal quantityReceived;

    private String unitOfMeasure;

    private Long unitCostMinor;

    private String lotNumber;

    /** UoM the line was keyed in, when it differed from base (odoo-parity B2, #1034). */
    @Column(name = "document_uom", length = 32)
    private String documentUom;

    /** Quantity as keyed in {@code documentUom}; {@code quantityShipped} holds the base quantity. */
    @Column(name = "document_quantity", precision = 18, scale = 6)
    private BigDecimal documentQuantity;

    /** Effective base-per-document-unit factor applied at conversion time (audit). */
    @Column(name = "conversion_factor", precision = 20, scale = 6)
    private BigDecimal conversionFactor;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
