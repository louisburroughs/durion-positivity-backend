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
 * Entity for tracking idempotency keys to prevent duplicate workorder creation.
 * 
 * <p>
 * This entity stores idempotency keys submitted with workorder creation
 * requests
 * to ensure that retrying a failed request with the same key does not create
 * duplicate workorders. Keys expire after 24 hours.
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_key_value", columnList = "keyValue", unique = true)
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

    @Column(nullable = false, unique = true, length = 255)
    private String keyValue;

    @Column(columnDefinition = "UUID")
    private UUID workorderId;

    @Column(columnDefinition = "UUID")
    private UUID changeRequestId;

    @Column(columnDefinition = "UUID")
    private UUID laborEntryId;

    @Column(columnDefinition = "UUID")
    private UUID partUsageEventId;

    @Column(columnDefinition = "UUID")
    private UUID partAdjustmentEventId;

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

    public IdempotencyKey(String keyValue, UUID workorderId, UUID changeRequestId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.workorderId = workorderId;
        this.changeRequestId = changeRequestId;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }
}
