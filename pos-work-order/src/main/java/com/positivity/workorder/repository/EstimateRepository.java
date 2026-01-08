package com.positivity.workorder.repository;

import com.positivity.workorder.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, Long> {
    List<Estimate> findByCustomerId(Long customerId);
    List<Estimate> findByShopId(Long shopId);
    List<Estimate> findByStatus(Estimate.EstimateStatus status);
}
