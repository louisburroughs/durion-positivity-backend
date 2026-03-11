package com.positivity.price.internal.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
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
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Promotion eligibility rule entity. Issue: #96 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(exclude = "promotion")
@ToString(exclude = "promotion")
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "promotion_eligibility_rule")
public class PromotionEligibilityRule {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false)
    private PromotionOffer promotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, columnDefinition = "VARCHAR(50)")
    private ConditionType conditionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, columnDefinition = "VARCHAR(50)")
    private RuleOperator operator;

    @Column(name = "rule_value", nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_combination", columnDefinition = "VARCHAR(10)")
    private RuleCombination ruleCombination = RuleCombination.AND;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public UUID getPromotionId() {
        return promotion == null ? null : promotion.getPromotionOfferId();
    }

}
