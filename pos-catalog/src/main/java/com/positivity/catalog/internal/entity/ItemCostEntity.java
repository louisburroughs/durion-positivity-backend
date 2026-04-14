package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "item_cost")
public class ItemCostEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "item_cost_id", nullable = false, columnDefinition = "UUID")
    private UUID itemCostId;

    @Column(name = "item_id", nullable = false, columnDefinition = "UUID", unique = true)
    private UUID itemId;

    @Column(name = "standard_cost", precision = 19, scale = 4)
    private BigDecimal standardCost;

    @Column(name = "last_cost", precision = 19, scale = 4)
    private BigDecimal lastCost;

    @Column(name = "average_cost", precision = 19, scale = 4)
    private BigDecimal averageCost;

    @Column(name = "qty_on_hand", nullable = false, precision = 19, scale = 4)
    private BigDecimal qtyOnHand;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (qtyOnHand == null) {
            qtyOnHand = BigDecimal.ZERO;
        }
    }
}
