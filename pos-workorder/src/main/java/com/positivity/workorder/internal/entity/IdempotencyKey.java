package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Entity for tracking idempotency keys to prevent duplicate workorder promotion processing.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_key_value", columnList = "keyValue")
})
public class IdempotencyKey {

    @EqualsAndHashCode.Include
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Column(nullable = false, unique = true)
    private String keyValue;

    @Column(nullable = false)
    private UUID workorderId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public IdempotencyKey(String keyValue, UUID workorderId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.workorderId = workorderId;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }
}
