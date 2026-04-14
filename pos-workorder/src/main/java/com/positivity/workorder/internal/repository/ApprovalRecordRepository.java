package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ApprovalRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, UUID> {
    List<ApprovalRecord> findByChangeRequest_Id(UUID changeRequestId);

    List<ApprovalRecord> findByWorkorder_Id(UUID workorderId);
}
