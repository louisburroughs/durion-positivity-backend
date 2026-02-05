package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkorderStateTransitionRepository extends JpaRepository<WorkorderStateTransition, UUID> {
    List<WorkorderStateTransition> findByWorkorderIdOrderByTransitionedAtDesc(Long workorderId);
}
