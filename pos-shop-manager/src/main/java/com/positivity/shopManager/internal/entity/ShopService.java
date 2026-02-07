package com.positivity.shopManager.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopService {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    // Reference to ServiceEntity in pos-catalog
    private Long serviceEntityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id")
    private Shop shop;
}

