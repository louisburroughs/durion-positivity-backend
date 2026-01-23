package com.positivity.workorder.repository;

import com.positivity.workorder.entity.WorkorderStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkorderStateTransitionRepository extends JpaRepository<WorkorderStateTransition, Long> {
    List<WorkorderStateTransition> findByWorkorderIdOrderByTransitionedAtDesc(Long workorderId);
}
