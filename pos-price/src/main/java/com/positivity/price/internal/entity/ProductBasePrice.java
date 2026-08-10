package com.positivity.price.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base price record for a product, retained as history: each row is one effective window
 * (half-open, {@code effectiveFrom <= t < effectiveTo}; null {@code effectiveTo} = open-ended)
 * per (productId, currency). Price changes append a new row and close the predecessor's window
 * rather than updating in place (ADR-0054 §4).
 *
 * Issue: #51, #1233
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "product_base_price")
public class ProductBasePrice {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal msrp;

    @Column(nullable = false, length = 3)
    private String currency;

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
