package com.positivity.accounting.internal.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Entity for tracking idempotency keys to prevent duplicate payment processing.
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

    @PrePersist
    public void generateId() {
    }

    @Column(nullable = false, unique = true)
    private String keyValue;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public IdempotencyKey(String keyValue, UUID invoiceId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.invoiceId = invoiceId;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }
}
