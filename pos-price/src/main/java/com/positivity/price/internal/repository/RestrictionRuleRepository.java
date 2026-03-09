package com.positivity.price.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.enums.LocationTag;
import com.positivity.price.enums.ServiceTag;

public interface RestrictionRuleRepository extends JpaRepository<RestrictionRule, UUID> {

    List<RestrictionRule> findByActiveTrue();

    Optional<RestrictionRule> findByRuleIdAndActiveTrue(UUID ruleId);

    List<RestrictionRule> findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
            UUID productId,
            LocationTag locationTag,
            ServiceTag serviceTag);
}
