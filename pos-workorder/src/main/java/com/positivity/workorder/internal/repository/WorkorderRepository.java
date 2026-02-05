package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Workorder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkorderRepository extends JpaRepository<Workorder, UUID> {
    /**
     * Find all WorkOrders associated with a specific Estimate
     * 
     * @param estimateId the ID of the estimate
     * @return list of WorkOrders linked to this estimate
     */
    List<Workorder> findByEstimateId(Long estimateId);
}
