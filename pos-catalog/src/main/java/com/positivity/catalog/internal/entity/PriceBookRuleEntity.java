package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "price_book_rule")
public class PriceBookRuleEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_book_id", nullable = false)
    private PriceBookEntity priceBook;

    @Column(name = "price_book_id", nullable = false, columnDefinition = "UUID", insertable = false, updatable = false)
    private UUID priceBookId;

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
    private OffsetDateTime effectiveStartAt;

    private OffsetDateTime effectiveEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PriceBookRuleStatus status;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID createdByUserId;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (ruleId == null) {
            ruleId = UUIDv7Generator.generate();
        }
        if (conditionType == null) {
            conditionType = PriceBookRuleConditionType.NONE;
        }
        if (status == null) {
            status = PriceBookRuleStatus.ACTIVE;
        }
        if (priority == null) {
            priority = 0;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
