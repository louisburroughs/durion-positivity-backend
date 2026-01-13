package com.positivity.workorder.repository;

import com.positivity.workorder.entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    /**
     * Find all WorkOrders associated with a specific Estimate
     * @param estimateId the ID of the estimate
     * @return list of WorkOrders linked to this estimate
     */
    List<WorkOrder> findByEstimateId(Long estimateId);
}

