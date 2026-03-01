package com.positivity.workorder.internal.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, UUID> {
    List<Estimate> findByCustomerId(UUID customerId);

    // Issue #15 (CAP-248): Paginated retrieval methods required for estimate search
    // endpoint.

    /**
     * Returns a page of estimates for a given customer.
     *
     * @param customerId filter by customer UUID
     * @param pageable   pagination configuration
     * @return page of matching estimates
     */
    Page<Estimate> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Returns a page of estimates for a given vehicle.
     *
     * @param vehicleId filter by vehicle UUID
     * @param pageable  pagination configuration
     * @return page of matching estimates
     */
    Page<Estimate> findByVehicleId(UUID vehicleId, Pageable pageable);

    /**
     * Returns a page of estimates matching both customer and vehicle.
     *
     * @param customerId filter by customer UUID
     * @param vehicleId  filter by vehicle UUID
     * @param pageable   pagination configuration
     * @return page of matching estimates
     */
    Page<Estimate> findByCustomerIdAndVehicleId(UUID customerId, UUID vehicleId, Pageable pageable);

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
