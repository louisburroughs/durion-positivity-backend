package com.positivity.workorder.repository;

import com.positivity.workorder.entity.WorkOrderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkOrderSnapshotRepository extends JpaRepository<WorkOrderSnapshot, Long> {
    List<WorkOrderSnapshot> findByWorkOrderIdOrderByCapturedAtDesc(Long workOrderId);
    List<WorkOrderSnapshot> findByWorkOrderIdAndSnapshotType(Long workOrderId, String snapshotType);
}
