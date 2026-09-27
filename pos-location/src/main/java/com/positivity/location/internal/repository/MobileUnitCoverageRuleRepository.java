package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for mobile unit coverage rules.
 *
 * Issue: #76
 */
public interface MobileUnitCoverageRuleRepository extends JpaRepository<MobileUnitCoverageRuleEntity, UUID> {

    List<MobileUnitCoverageRuleEntity> findByMobileUnit_IdOrderByPriorityAsc(UUID mobileUnitId);

    /** Every rule of a page of units in one query, for the list's {@code include=coverageRules} (#2253). */
    List<MobileUnitCoverageRuleEntity> findByMobileUnit_IdInOrderByPriorityAsc(Collection<UUID> mobileUnitIds);

    void deleteByMobileUnit_Id(UUID mobileUnitId);

    /**
     * Rules eligible for an address on a given instant, scoped to one base location
     * (DECISION-LOCATION-027): the unit is ACTIVE and based there, the rule's service area is
     * {@code active} (an inactive area contributes no coverage, though its rules are kept), the
     * area covers the postal code, and {@code at} falls in the rule's validity window ({@code
     * validFrom} inclusive, {@code validTo} exclusive). Ordered by priority ascending, then unit id,
     * so the ranking is one deterministic sequence across the location's units rather than per rule.
     */
    @Query("""
            select r from MobileUnitCoverageRuleEntity r
            join r.mobileUnit mu
            join r.serviceArea sa
            join sa.postalCodes pc
            where upper(mu.status) = 'ACTIVE'
              and sa.active = true
              and mu.baseLocation.id = :baseLocationId
              and (r.validFrom is null or r.validFrom <= :at)
              and (r.validTo is null or r.validTo > :at)
              and pc.postalCode = :postalCode
              and upper(pc.countryCode) = upper(:countryCode)
            order by r.priority asc, mu.id asc
            """)
    List<MobileUnitCoverageRuleEntity> findEligibleCoverageRules(
            String postalCode, String countryCode, Instant at, UUID baseLocationId);
}
