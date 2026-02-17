package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "product_replacement")
public class ProductReplacementEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID replacementId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID originalProductId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID replacementProductId;

    @Column(nullable = false)
    private Integer priorityOrder;

    private String notes;

    private Instant effectiveAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deletedAt;

    @jakarta.persistence.PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (replacementId == null) {
            replacementId = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
