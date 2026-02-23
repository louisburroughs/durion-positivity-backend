package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "cost_tier")
public class CostTierEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_item_cost_id", nullable = false)
    private SupplierItemCostEntity supplierItemCost;

    @Column(name = "supplier_item_cost_id", insertable = false, updatable = false, columnDefinition = "UUID")
    private UUID supplierItemCostId;

    @Column(name = "min_quantity", nullable = false)
    private Integer minQuantity;

    @Column(name = "max_quantity")
    private Integer maxQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    public void setSupplierItemCost(SupplierItemCostEntity supplierItemCost) {
        this.supplierItemCost = supplierItemCost;
    }

}
