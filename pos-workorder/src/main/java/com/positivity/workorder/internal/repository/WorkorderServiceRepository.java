package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkorderServiceRepository extends JpaRepository<WorkorderService, Long> {
    List<WorkorderService> findByChangeRequestId(Long changeRequestId);
}

