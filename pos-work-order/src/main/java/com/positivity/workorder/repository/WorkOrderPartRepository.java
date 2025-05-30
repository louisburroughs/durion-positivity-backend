package com.positivity.workorder.repository;

import com.positivity.workorder.entity.WorkOrderPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkOrderPartRepository extends JpaRepository<WorkOrderPart, Long> {
}

