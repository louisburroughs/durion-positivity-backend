package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PutawayRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PutawayRuleRepository extends JpaRepository<PutawayRule, UUID> {

    List<PutawayRule> findAllByIsEnabledTrueOrderByPriorityAsc();

    boolean existsByDestinationLocationIdAndIsEnabledTrue(UUID destinationLocationId);
}
