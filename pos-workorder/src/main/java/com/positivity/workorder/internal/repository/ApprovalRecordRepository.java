package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {
    List<ApprovalRecord> findByChangeRequestId(Long changeRequestId);
    List<ApprovalRecord> findByWorkorderId(Long workorderId);
}
