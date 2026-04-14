package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkorderSnapshotRepository extends JpaRepository<WorkorderSnapshot, UUID> {
    List<WorkorderSnapshot> findByWorkorder_IdOrderByCapturedAtDesc(UUID workOrderId);

    List<WorkorderSnapshot> findByWorkorder_IdAndSnapshotType(UUID workOrderId, String snapshotType);
}
