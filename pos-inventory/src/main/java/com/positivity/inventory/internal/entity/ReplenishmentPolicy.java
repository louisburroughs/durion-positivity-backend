package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "replenishment_policy")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplenishmentPolicy {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID policyId;

    @Column(nullable = false)
    private String locationId;

    @Column(nullable = false)
    private String itemSKU;

    @Column(nullable = false)
    private Integer minimumQuantity;

    @Column(nullable = false)
    private Integer maximumQuantity;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
