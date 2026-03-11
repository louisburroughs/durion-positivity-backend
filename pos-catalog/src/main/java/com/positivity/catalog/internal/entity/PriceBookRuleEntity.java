package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "price_book_rule")
public class PriceBookRuleEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_book_id", nullable = false)
    private PriceBookEntity priceBook;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PriceBookRuleTargetType targetType;

    @Column(columnDefinition = "UUID")
    private UUID targetId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String pricingLogic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PriceBookRuleConditionType conditionType;

    @Column(length = 255)
    private String conditionValue;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Instant effectiveStartAt;

    private Instant effectiveEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PriceBookRuleStatus status;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID createdByUserId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (conditionType == null) {
            conditionType = PriceBookRuleConditionType.NONE;
        }
        if (status == null) {
            status = PriceBookRuleStatus.ACTIVE;
        }
        if (priority == null) {
            priority = 0;
        }
    }
}
