package com.positivity.catalog.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "item_cost")
public class ItemCostEntity {

    @Id
    @Column(name = "item_id", nullable = false, columnDefinition = "UUID")
    private UUID itemId;

    @Column(name = "standard_cost", precision = 19, scale = 4)
    private BigDecimal standardCost;

    @Column(name = "last_cost", precision = 19, scale = 4)
    private BigDecimal lastCost;

    @Column(name = "average_cost", precision = 19, scale = 4)
    private BigDecimal averageCost;

    @Column(name = "qty_on_hand", nullable = false, precision = 19, scale = 4)
    private BigDecimal qtyOnHand;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (qtyOnHand == null) {
            qtyOnHand = BigDecimal.ZERO;
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
