package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkorderServiceRepository extends JpaRepository<WorkorderService, UUID> {
    List<WorkorderService> findByChangeRequestId(UUID changeRequestId);

    List<WorkorderService> findByWorkOrder_Id(UUID workorderId);
}
