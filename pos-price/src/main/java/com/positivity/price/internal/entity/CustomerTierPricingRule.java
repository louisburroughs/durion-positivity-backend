package com.positivity.price.internal.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;
/**
 * Customer tier discount pricing rule for a product.
 *
 * Issue: #51
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "customer_tier_pricing_rule", indexes = {
        @Index(name = "idx_tier_rule_product_tier_effective", columnList = "product_id,customer_tier_id,effective_from,effective_to")
})
public class CustomerTierPricingRule {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID id;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private UUID customerTierId;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRate;

    @Column(nullable = false)
    private Instant effectiveFrom;

    @Nullable
    private Instant effectiveTo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public UUID getCustomerTierId() {
        return customerTierId;
    }

    public void setCustomerTierId(UUID customerTierId) {
        this.customerTierId = customerTierId;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public void setDiscountRate(BigDecimal discountRate) {
        this.discountRate = discountRate;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
}

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
}
}
