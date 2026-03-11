package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.PromotionEligibilityRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromotionEligibilityRuleRepository extends JpaRepository<PromotionEligibilityRule, UUID> {
    List<PromotionEligibilityRule> findByPromotion_PromotionOfferId(UUID promotionId);

    long deleteByRuleIdAndPromotion_PromotionOfferId(UUID ruleId, UUID promotionId);
}
