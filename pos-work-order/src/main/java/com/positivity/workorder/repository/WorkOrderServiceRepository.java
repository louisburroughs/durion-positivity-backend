package com.positivity.workorder.repository;

import com.positivity.workorder.entity.WorkOrderService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkOrderServiceRepository extends JpaRepository<WorkOrderService, Long> {
}

