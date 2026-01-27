package com.positivity.workorder.repository;

import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.EstimateStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, Long> {
    List<Estimate> findByCustomerId(Long customerId);

    @Deprecated
    @Query("SELECT e FROM Estimate e WHERE e.locationId = ?1")
    List<Estimate> findByShopId(Long locationId);

    List<Estimate> findByLocationId(Long locationId);

    List<Estimate> findByStatus(EstimateStatus status);

    boolean existsByLocationIdAndEstimateNumber(Long locationId, String estimateNumber);
}
