package com.positivity.price.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

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
@Data
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

}