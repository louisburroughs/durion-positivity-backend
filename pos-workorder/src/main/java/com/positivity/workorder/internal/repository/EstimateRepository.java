package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, UUID> {
    List<Estimate> findByCustomerId(UUID customerId);

    @Deprecated
    @Query("SELECT e FROM Estimate e WHERE e.locationId = ?1")
    List<Estimate> findByShopId(UUID locationId);

    List<Estimate> findByLocationId(UUID locationId);

    List<Estimate> findByStatus(EstimateStatus status);

    boolean existsByLocationIdAndEstimateNumber(UUID locationId, String estimateNumber);

    /**
     * Find estimates by status where expiration timestamp is before the given date.
     * Used by approval expiration job to find expired pending approvals.
     * CAP:003 Issue #204 - Handle Approval Expiration
     * 
     * @param status          estimate status to filter by
     * @param expiresAtBefore find estimates expired before this timestamp
     * @return list of expired estimates in the given status
     */
    List<Estimate> findByStatusAndExpiresAtBefore(EstimateStatus status, LocalDateTime expiresAtBefore);
}
