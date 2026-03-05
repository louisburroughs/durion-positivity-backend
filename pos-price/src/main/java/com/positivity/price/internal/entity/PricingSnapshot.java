package com.positivity.price.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;

/**
 * Immutable pricing snapshot persisted for pricing audit and replay.
 *
 * Issue: #50
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "pricing_snapshot")
public class PricingSnapshot {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID snapshotId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(columnDefinition = "TEXT")
    @Nullable
    private String sourceContext;

    @Column(nullable = false)
    private String itemIdentifier;

    @Column(nullable = false)
    private Integer quantity;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String prices;

    @Column(columnDefinition = "TEXT")
    @Nullable
    private String appliedRules;

    @Column(nullable = false)
    private String policyVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
