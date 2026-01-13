package com.positivity.order.repository;

import com.positivity.order.model.OverrideStatus;
import com.positivity.order.model.PriceOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for PriceOverride entity operations.
 */
@Repository
public interface PriceOverrideRepository extends JpaRepository<PriceOverride, Long> {
    
    /**
     * Find all overrides for a specific order.
     */
    List<PriceOverride> findByOrderId(String orderId);
    
    /**
     * Find override for a specific order line.
     */
    Optional<PriceOverride> findByOrderIdAndOrderLineId(String orderId, String orderLineId);
    
    /**
     * Find all overrides by status.
     */
    List<PriceOverride> findByStatus(OverrideStatus status);
    
    /**
     * Find all overrides requested by a user.
     */
    List<PriceOverride> findByRequestedByUserId(String userId);
    
    /**
     * Find all pending approvals.
     */
    List<PriceOverride> findByStatusAndRequiresApproval(OverrideStatus status, Boolean requiresApproval);
    
    /**
     * Find overrides created within a date range.
     */
    List<PriceOverride> findByCreatedAtBetween(Instant startDate, Instant endDate);
    
    /**
     * Find overrides approved by a specific manager.
     */
    List<PriceOverride> findByApprovedByUserId(String userId);
}
