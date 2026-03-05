package com.positivity.accounting.internal.entity;

import java.time.Clock;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.annotation.CreatedDate;
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
    @Column(nullable = false, unique = true)
    private String keyValue;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    @CreatedDate
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public IdempotencyKey(String keyValue, UUID invoiceId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.invoiceId = invoiceId;
        this.expiresAt = expiresAt;
    }
}
