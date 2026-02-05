package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, UUID> {
    List<Estimate> findByCustomerId(Long customerId);

    @Deprecated
    @Query("SELECT e FROM Estimate e WHERE e.locationId = ?1")
    List<Estimate> findByShopId(Long locationId);

    List<Estimate> findByLocationId(Long locationId);

    List<Estimate> findByStatus(EstimateStatus status);

    boolean existsByLocationIdAndEstimateNumber(Long locationId, String estimateNumber);
}
