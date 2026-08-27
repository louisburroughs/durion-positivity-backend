package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PutawayRuleRepository extends JpaRepository<PutawayRule, UUID> {

    /**
     * Every enabled rule, most-preferred-first inside each tier. The matcher loads this once per
     * receipt and partitions it by {@code matchType} in memory rather than issuing one query per
     * tier per line: the enabled rule set is operator-authored configuration, so it is small, and a
     * single ordered read keeps a multi-line receipt at one query instead of four per line.
     *
     * <p>{@code ruleId} is a secondary sort key, not decoration. Nothing stops an operator giving two
     * rules in the same tier the same priority, and the matcher takes the first match in this order —
     * so without a total order the destination a receipt is routed to would depend on physical row
     * order and could change after any update or vacuum. A tie is still arbitrary; it is now at least
     * the same arbitrary answer every time, which is what makes a re-generated receipt reproducible.
     */
    List<PutawayRule> findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc();

    boolean existsByDestinationLocationIdAndIsEnabledTrue(UUID destinationLocationId);

    /** Enabled rules in one tier, used to enforce the single-enabled-ANY-rule invariant. */
    List<PutawayRule> findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType matchType);
}
