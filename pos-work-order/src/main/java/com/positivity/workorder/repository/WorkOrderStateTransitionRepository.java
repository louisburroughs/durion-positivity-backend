package com.positivity.workorder.repository;

import com.positivity.workorder.entity.WorkOrderStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkOrderStateTransitionRepository extends JpaRepository<WorkOrderStateTransition, Long> {
    List<WorkOrderStateTransition> findByWorkOrderIdOrderByTransitionedAtDesc(Long workOrderId);
}
