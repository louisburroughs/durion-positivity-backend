package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkorderServiceRepository extends JpaRepository<WorkorderServiceLine, UUID> {
    List<WorkorderServiceLine> findByChangeRequest_Id(UUID changeRequestId);

    List<WorkorderServiceLine> findByWorkOrder_Id(UUID workorderId);
}
