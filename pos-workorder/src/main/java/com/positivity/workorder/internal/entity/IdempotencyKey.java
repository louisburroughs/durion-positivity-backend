package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
@EntityListeners(AuditingEntityListener.class)
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_key_value", columnList = "keyValue", unique = true)
})
public class IdempotencyKey {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

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

    @Column(columnDefinition = "UUID")
    private UUID invoiceId;

    @CreatedDate
    @Column(nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    public IdempotencyKey(String keyValue, UUID workorderId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.workorderId = workorderId;
        this.expiresAt = expiresAt;
    }

    public IdempotencyKey(String keyValue, UUID workorderId, UUID changeRequestId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.workorderId = workorderId;
        this.changeRequestId = changeRequestId;
        this.expiresAt = expiresAt;
    }
}
