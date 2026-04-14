package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.enums.ApprovalTier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
