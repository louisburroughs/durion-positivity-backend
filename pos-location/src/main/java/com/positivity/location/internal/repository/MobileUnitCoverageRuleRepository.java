package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import java.time.LocalDate;
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

    void deleteByMobileUnit_Id(UUID mobileUnitId);

    @Query("""
            select r from MobileUnitCoverageRuleEntity r
            join r.mobileUnit mu
            join r.serviceArea sa
            join sa.postalCodes pc
            where upper(mu.status) = 'ACTIVE'
              and (r.validFrom is null or r.validFrom <= :atDate)
              and (r.validTo is null or r.validTo >= :atDate)
              and pc.postalCode = :postalCode
              and upper(pc.countryCode) = upper(:countryCode)
            order by r.priority asc
            """)
    List<MobileUnitCoverageRuleEntity> findEligibleCoverageRules(
            String postalCode, String countryCode, LocalDate atDate);
}
