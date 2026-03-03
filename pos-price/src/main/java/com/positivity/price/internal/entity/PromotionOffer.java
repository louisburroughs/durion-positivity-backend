package com.positivity.price.internal.entity;

import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Promotion offer aggregate root for pricing promotions.
 *
 * Issue: #97
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "promotion_offer")
public class PromotionOffer {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID promotionOfferId;

    @Column(unique = true, nullable = false)
    private String promoCode;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType discountType;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal discountValue;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column
    private Integer usageLimit;

    @Column(nullable = false, columnDefinition = "int default 0")
    private int usageCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromotionStatus status = PromotionStatus.DRAFT;

    @Column
    private String storeCode;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @Column
    private String createdBy;

    @PrePersist
    void initId() {
        if (this.promotionOfferId == null) {
            this.promotionOfferId = UUIDv7Generator.generate();
        }
    }
}
