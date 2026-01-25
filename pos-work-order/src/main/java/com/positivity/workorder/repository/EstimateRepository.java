package com.positivity.workorder.repository;

import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.EstimateStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, Long> {
    List<Estimate> findByCustomerId(Long customerId);

    @Deprecated
    List<Estimate> findByShopId(Long shopId);

    List<Estimate> findByLocationId(Long locationId);

    List<Estimate> findByStatus(EstimateStatus status);

    boolean existsByLocationIdAndEstimateNumber(Long locationId, String estimateNumber);
}
