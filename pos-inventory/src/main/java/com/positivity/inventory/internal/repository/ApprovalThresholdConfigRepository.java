package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.internal.model.cyclecount.ApprovalThresholdConfig;
import com.positivity.inventory.internal.model.cyclecount.ApprovalTier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ApprovalThresholdConfig} entities.
 */
@Repository
public interface ApprovalThresholdConfigRepository extends JpaRepository<ApprovalThresholdConfig, UUID> {
    
    /**
     * Find configuration for a specific approval tier.
     * 
     * @param approvalTier the approval tier
     * @return optional configuration
     */
    Optional<ApprovalThresholdConfig> findByApprovalTier(ApprovalTier approvalTier);
    
    /**
     * Find all active configurations ordered by tier.
     * 
     * @return list of active threshold configurations
     */
    List<ApprovalThresholdConfig> findByActiveTrueOrderByApprovalTierAsc();
}
