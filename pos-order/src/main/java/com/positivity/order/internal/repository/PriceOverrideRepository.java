package com.positivity.order.internal.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverride;

/**
 * Repository for PriceOverride entity operations.
 */
@Repository
public interface PriceOverrideRepository extends JpaRepository<PriceOverride, UUID> {

    /**
     * Find all overrides for a specific order.
     */
    List<PriceOverride> findByOrderId(UUID orderId);

    /**
     * Find override for a specific order line.
     */
    Optional<PriceOverride> findByOrderIdAndOrderLineId(UUID orderId, UUID orderLineId);

    /**
     * Find all overrides by status.
     */
    List<PriceOverride> findByStatus(OverrideStatus status);

    /**
     * Find all overrides requested by a user.
     */
    List<PriceOverride> findByRequestedByUserId(UUID userId);

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
    List<PriceOverride> findByApprovedByUserId(UUID userId);

    Optional<PriceOverride> findByIdempotencyKey(String idempotencyKey);
}
