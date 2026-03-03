package com.positivity.customer.internal.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.customer.internal.enums.RedemptionStatus;
import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "promotion_redemption", uniqueConstraints = @UniqueConstraint(name = "uq_promotion_workorder", columnNames = {
    "promotion_id", "workorder_id" }))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class PromotionRedemption {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "promotion_redemption_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID promotionRedemptionId;

    @Column(name = "promotion_id", nullable = false)
    private UUID promotionId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "discount_type", columnDefinition = "VARCHAR(30)", nullable = false)
    private String discountType;

    @Column(name = "promotion_code", nullable = false)
    private String promotionCode;

    @Column(name = "recorded_over_limit")
    private Boolean recordedOverLimit = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "VARCHAR(30)", nullable = false)
    private RedemptionStatus status = RedemptionStatus.RECORDED;

    @Column(name = "redemption_timestamp")
    private LocalDateTime redemptionTimestamp;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}