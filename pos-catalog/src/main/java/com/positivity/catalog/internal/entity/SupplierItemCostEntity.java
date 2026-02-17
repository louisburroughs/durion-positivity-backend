package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "supplier_item_cost",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_item_cost_supplier_item", columnNames = {
                "supplier_id", "item_id"
        }))
public class SupplierItemCostEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "supplier_id", nullable = false, columnDefinition = "UUID")
    private UUID supplierId;

    @Column(name = "item_id", nullable = false, columnDefinition = "UUID")
    private UUID itemId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "base_cost", precision = 19, scale = 4)
    private BigDecimal baseCost;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "supplierItemCost", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("minQuantity ASC")
    private List<CostTierEntity> costTiers = new ArrayList<>();

    public void setCostTiers(List<CostTierEntity> tiers) {
        costTiers.clear();
        if (tiers == null) {
            return;
        }
        for (CostTierEntity tier : tiers) {
            tier.setSupplierItemCost(this);
            costTiers.add(tier);
        }
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
