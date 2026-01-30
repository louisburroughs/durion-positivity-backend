package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkorderSnapshotRepository extends JpaRepository<WorkorderSnapshot, Long> {
    List<WorkorderSnapshot> findByWorkorderIdOrderByCapturedAtDesc(Long workOrderId);
    List<WorkorderSnapshot> findByWorkorderIdAndSnapshotType(Long workOrderId, String snapshotType);
}
