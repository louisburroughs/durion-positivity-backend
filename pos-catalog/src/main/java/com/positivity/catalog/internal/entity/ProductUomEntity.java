package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-product UoM conversion: one purchase/pack (or base) unit of measure with its factor to the
 * product's base UoM and a precision scale (#1023, inventory Odoo-parity Story X1). Distinct from
 * the global {@link UomConversionEntity} pair table — these rows are product-scoped and travel on
 * the {@code catalog.product.updated} event for the pos-inventory {@code ext_product_uom} replica.
 */
@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "product_uom",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_product_uom_product_code",
                        columnNames = {"product_id", "uom_code"}),
        indexes = @Index(name = "idx_product_uom_product", columnList = "product_id"))
public class ProductUomEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(name = "uom_code", nullable = false, length = 32)
    private String uomCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "uom_type", nullable = false, length = 16)
    private ProductUomType uomType;

    /** Multiplier converting one unit of this UoM into the product's base UoM. */
    @Column(name = "factor_to_base", nullable = false, precision = 20, scale = 6)
    private BigDecimal factorToBase;

    /** Decimal precision scale for quantities expressed in this UoM. */
    @Column(name = "precision_scale", nullable = false)
    private int precisionScale;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
