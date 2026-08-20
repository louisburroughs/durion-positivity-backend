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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "inventory_return_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryReturnLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private InventoryReturnEntity inventoryReturn;

    @Column(nullable = false)
    private UUID skuId;

    /** Base-UoM quantity returned; decimal-capable per the product's declaration (ADR-0055). */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityReturned;

    /** UoM the line was keyed in, when it differed from base (odoo-parity B2, #1034). */
    @Column(name = "document_uom", length = 32)
    private String documentUom;

    /** Quantity as keyed in {@code documentUom}; {@code quantityReturned} holds the base quantity. */
    @Column(name = "document_quantity", precision = 18, scale = 6)
    private BigDecimal documentQuantity;

    /** Effective base-per-document-unit factor applied at conversion time (audit). */
    @Column(name = "conversion_factor", precision = 20, scale = 6)
    private BigDecimal conversionFactor;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
