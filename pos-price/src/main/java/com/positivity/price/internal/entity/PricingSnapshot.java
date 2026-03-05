package com.positivity.price.internal.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    public UUID getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(UUID snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
}

    public String getSourceContext() {
        return sourceContext;
    }

    public void setSourceContext(String sourceContext) {
        this.sourceContext = sourceContext;
    }

    public String getItemIdentifier() {
        return itemIdentifier;
    }

    public void setItemIdentifier(String itemIdentifier) {
        this.itemIdentifier = itemIdentifier;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getPrices() {
        return prices;
    }

    public void setPrices(String prices) {
        this.prices = prices;
    }

    public String getAppliedRules() {
        return appliedRules;
    }

    public void setAppliedRules(String appliedRules) {
        this.appliedRules = appliedRules;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
}
}
