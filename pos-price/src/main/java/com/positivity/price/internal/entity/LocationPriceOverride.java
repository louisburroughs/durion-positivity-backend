package com.positivity.price.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * Location-specific absolute price override for a product.
 *
 * Issue: #51
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "location_price_override",
        indexes = {
            @Index(
                    name = "idx_location_override_product_location_effective",
                    columnList = "product_id,location_id,effective_from,effective_to")
        })
public class LocationPriceOverride {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID id;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private UUID locationId;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal overridePrice;

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
